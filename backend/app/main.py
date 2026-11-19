from __future__ import annotations
import base64, hashlib, hmac, json, os, secrets, sqlite3, stat, time, uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

class EnrollRequest(BaseModel):
    label: str = Field(min_length=1, max_length=80)

class PublishConfigRequest(BaseModel):
    payload: dict
    expires_at: int = Field(gt=0)

def create_app(*, database_path=None, admin_token=None, signing_key_pem=None) -> FastAPI:
    db_path = Path(database_path or os.environ.get("MOLIDO_DB_PATH", "./data/molido.sqlite3"))
    admin_secret = admin_token if admin_token is not None else os.environ.get("MOLIDO_ADMIN_TOKEN", "")
    private_pem = signing_key_pem
    if private_pem is None:
        key_path = os.environ.get("MOLIDO_CONFIG_SIGNING_KEY_FILE")
        if key_path:
            private_pem = Path(key_path).read_bytes()
    app = FastAPI(title="MolidoVPN Self-hosted Control Plane", version="0.1.0", docs_url=None, redoc_url=None)

    def connect_db():
        # The parent directory also contains SQLite WAL/SHM sidecars. Keep the
        # entire directory owner-only instead of relying on umask or convention.
        db_path.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
        if os.name == "posix":
            directory_stat = db_path.parent.stat()
            if directory_stat.st_uid != os.geteuid() or stat.S_IMODE(directory_stat.st_mode) != 0o700:
                raise HTTPException(status_code=503, detail="Database directory must be owned by the service user and have mode 0700")
            flags = os.O_CREAT | os.O_RDWR
            if hasattr(os, "O_NOFOLLOW"):
                flags |= os.O_NOFOLLOW
            try:
                fd = os.open(db_path, flags, 0o600)
            except OSError:
                raise HTTPException(status_code=503, detail="Database file cannot be opened safely")
            try:
                file_stat = os.fstat(fd)
                if not stat.S_ISREG(file_stat.st_mode) or file_stat.st_uid != os.geteuid():
                    raise HTTPException(status_code=503, detail="Database path must be a regular file owned by the service user")
                os.fchmod(fd, 0o600)
            finally:
                os.close(fd)
        conn = sqlite3.connect(db_path, timeout=10)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA foreign_keys=ON")
        conn.executescript("""
        CREATE TABLE IF NOT EXISTS devices(device_id TEXT PRIMARY KEY,label TEXT NOT NULL,token_hash TEXT NOT NULL,created_at INTEGER NOT NULL,revoked_at INTEGER);
        CREATE TABLE IF NOT EXISTS configurations(device_id TEXT PRIMARY KEY REFERENCES devices(device_id),revision INTEGER NOT NULL,expires_at INTEGER NOT NULL,payload BLOB NOT NULL,signature BLOB NOT NULL);
        CREATE TABLE IF NOT EXISTS audit_events(event_id INTEGER PRIMARY KEY AUTOINCREMENT,event_type TEXT NOT NULL,device_id TEXT,occurred_at INTEGER NOT NULL);
        """)
        return conn

    @contextmanager
    def db() -> Iterator[sqlite3.Connection]:
        conn = connect_db()
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    def require_admin(authorization: str | None = Header(default=None)):
        if len(admin_secret) < 32 or not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(status_code=503, detail="Admin authentication is not configured")
        if not hmac.compare_digest(authorization[7:], admin_secret):
            raise HTTPException(status_code=401, detail="Unauthorized")

    def require_device(authorization: str | None = Header(default=None)) -> str:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(status_code=401, detail="Device token required")
        token_hash = hashlib.sha256(authorization[7:].encode()).hexdigest()
        with db() as conn:
            row = conn.execute("SELECT device_id FROM devices WHERE token_hash=? AND revoked_at IS NULL", (token_hash,)).fetchone()
        if row is None:
            raise HTTPException(status_code=401, detail="Invalid or revoked device token")
        return str(row["device_id"])

    def load_signing_key():
        if not private_pem:
            raise HTTPException(status_code=503, detail="Configuration signing key is not configured")
        try:
            key = serialization.load_pem_private_key(private_pem, password=None)
        except (ValueError, TypeError):
            raise HTTPException(status_code=503, detail="Configuration signing key is invalid")
        if not isinstance(key, ec.EllipticCurvePrivateKey) or key.curve.name != "secp256r1":
            raise HTTPException(status_code=503, detail="An EC signing key is required")
        return key

    @app.get("/health/live")
    def live():
        return {"status": "alive"}

    @app.get("/health/ready")
    def ready():
        if len(admin_secret) < 32:
            raise HTTPException(status_code=503, detail="Admin authentication is not securely configured")
        with db() as conn:
            conn.execute("SELECT 1").fetchone()
        load_signing_key()
        return {"status": "ready", "database": "reachable", "signing_key_configured": True}

    @app.post("/v1/devices/enroll")
    def enroll(body: EnrollRequest, _: None = Depends(require_admin)):
        device_id, token, now = str(uuid.uuid4()), secrets.token_urlsafe(32), int(time.time())
        with db() as conn:
            conn.execute("INSERT INTO devices(device_id,label,token_hash,created_at) VALUES(?,?,?,?)",
                         (device_id, body.label, hashlib.sha256(token.encode()).hexdigest(), now))
            conn.execute("INSERT INTO audit_events(event_type,device_id,occurred_at) VALUES(?,?,?)",
                         ("DEVICE_ENROLLED", device_id, now))
        return {"device_id": device_id, "device_token": token}

    @app.post("/v1/devices/{device_id}/revoke")
    def revoke(device_id: str, _: None = Depends(require_admin)):
        now = int(time.time())
        with db() as conn:
            result = conn.execute("UPDATE devices SET revoked_at=? WHERE device_id=? AND revoked_at IS NULL", (now, device_id))
            if result.rowcount != 1:
                raise HTTPException(status_code=404, detail="Active device not found")
            conn.execute("INSERT INTO audit_events(event_type,device_id,occurred_at) VALUES(?,?,?)",
                         ("DEVICE_REVOKED", device_id, now))
        return {"device_id": device_id, "revoked": True}

    @app.put("/v1/devices/{device_id}/configuration")
    def publish_config(device_id: str, body: PublishConfigRequest, _: None = Depends(require_admin)):
        key = load_signing_key()
        try:
            # RFC-compliant JSON only: Python's default encoder otherwise emits
            # NaN/Infinity tokens that are not valid interoperable JSON.
            payload = json.dumps(
                body.payload, sort_keys=True, separators=(",", ":"),
                ensure_ascii=False, allow_nan=False
            ).encode()
        except (TypeError, ValueError):
            raise HTTPException(status_code=422, detail="Configuration payload must be valid JSON")
        if not payload or len(payload) > 65536:
            raise HTTPException(status_code=413, detail="Configuration payload exceeds 64 KiB")
        now = int(time.time())
        if body.expires_at <= now:
            raise HTTPException(status_code=400, detail="Configuration expiry must be in the future")
        with db() as conn:
            device = conn.execute("SELECT device_id FROM devices WHERE device_id=? AND revoked_at IS NULL", (device_id,)).fetchone()
            if device is None:
                raise HTTPException(status_code=404, detail="Active device not found")
            current = conn.execute("SELECT revision FROM configurations WHERE device_id=?", (device_id,)).fetchone()
            revision = int(current["revision"]) + 1 if current else 1
            signed_payload = f"{revision}\n{body.expires_at}\n".encode() + payload
            signature = key.sign(signed_payload, ec.ECDSA(hashes.SHA256()))
            conn.execute("""INSERT INTO configurations(device_id,revision,expires_at,payload,signature)
                VALUES(?,?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET revision=excluded.revision,
                expires_at=excluded.expires_at,payload=excluded.payload,signature=excluded.signature""",
                (device_id, revision, body.expires_at, payload, signature))
            conn.execute("INSERT INTO audit_events(event_type,device_id,occurred_at) VALUES(?,?,?)",
                         ("CONFIG_PUBLISHED", device_id, now))
        return {"device_id": device_id, "revision": revision, "expires_at": body.expires_at}

    @app.get("/v1/configuration")
    def get_config(device_id: str = Depends(require_device)):
        with db() as conn:
            row = conn.execute("SELECT revision,expires_at,payload,signature FROM configurations WHERE device_id=?", (device_id,)).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="No configuration is published")
        if int(row["expires_at"]) <= int(time.time()):
            raise HTTPException(status_code=410, detail="Configuration has expired")
        return {"revision": int(row["revision"]), "expires_at": int(row["expires_at"]),
                "payload_base64": base64.b64encode(row["payload"]).decode(),
                "signature_base64": base64.b64encode(row["signature"]).decode()}
    return app

app = create_app()
