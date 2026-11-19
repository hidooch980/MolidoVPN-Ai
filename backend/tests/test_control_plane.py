import base64, time
from fastapi.testclient import TestClient
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from backend.app.main import create_app

def setup_client(tmp_path):
    key = ec.generate_private_key(ec.SECP256R1())
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    return TestClient(create_app(database_path=tmp_path / "control.sqlite3", admin_token="molido-test-admin-token-32chars-minimum", signing_key_pem=pem)), key

def test_enroll_publish_and_fetch_signed_configuration(tmp_path):
    client, key = setup_client(tmp_path)
    admin = {"Authorization": "Bearer molido-test-admin-token-32chars-minimum"}
    enrolled = client.post("/v1/devices/enroll", headers=admin, json={"label": "android-test"})
    assert enrolled.status_code == 200
    credentials = enrolled.json()
    expires = int(time.time()) + 600
    published = client.put(f"/v1/devices/{credentials['device_id']}/configuration", headers=admin,
                           json={"payload": {"policy": "wifi-only"}, "expires_at": expires})
    assert published.status_code == 200
    result = client.get("/v1/configuration", headers={"Authorization": f"Bearer {credentials['device_token']}"})
    assert result.status_code == 200
    payload = base64.b64decode(result.json()["payload_base64"])
    signature = base64.b64decode(result.json()["signature_base64"])
    canonical = f"{result.json()['revision']}\n{expires}\n".encode() + payload
    key.public_key().verify(signature, canonical, ec.ECDSA(hashes.SHA256()))

def test_revoked_device_cannot_fetch_configuration(tmp_path):
    client, _ = setup_client(tmp_path)
    admin = {"Authorization": "Bearer molido-test-admin-token-32chars-minimum"}
    credentials = client.post("/v1/devices/enroll", headers=admin, json={"label": "phone"}).json()
    assert client.post(f"/v1/devices/{credentials['device_id']}/revoke", headers=admin).status_code == 200
    response = client.get("/v1/configuration", headers={"Authorization": f"Bearer {credentials['device_token']}"})
    assert response.status_code == 401

def test_invalid_admin_token_is_rejected(tmp_path):
    client, _ = setup_client(tmp_path)
    response = client.post("/v1/devices/enroll", headers={"Authorization": "Bearer wrong"}, json={"label": "phone"})
    assert response.status_code == 401


def test_readiness_requires_operator_signing_key(tmp_path):
    app = create_app(database_path=tmp_path / "no-key.sqlite3", admin_token="molido-test-admin-token-32chars-minimum")
    response = TestClient(app).get("/health/ready")
    assert response.status_code == 503


def test_short_admin_secret_fails_closed(tmp_path):
    client = TestClient(create_app(database_path=tmp_path / "short-token.sqlite3", admin_token="short"))
    response = client.post(
        "/v1/devices/enroll",
        headers={"Authorization": "Bearer short"},
        json={"label": "phone"},
    )
    assert response.status_code == 503


def test_expired_configuration_is_rejected(tmp_path):
    client, _ = setup_client(tmp_path)
    admin = {"Authorization": "Bearer molido-test-admin-token-32chars-minimum"}
    credentials = client.post("/v1/devices/enroll", headers=admin, json={"label": "phone"}).json()
    response = client.put(
        f"/v1/devices/{credentials['device_id']}/configuration",
        headers=admin,
        json={"payload": {"policy": "wifi-only"}, "expires_at": int(time.time()) - 1},
    )
    assert response.status_code == 400


def test_readiness_rejects_short_admin_secret(tmp_path):
    app = create_app(
        database_path=tmp_path / "short-admin.sqlite3",
        admin_token="short",
        signing_key_pem=ec.generate_private_key(ec.SECP256R1()).private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ),
    )
    response = TestClient(app).get("/health/ready")
    assert response.status_code == 503


def test_database_file_is_owner_only_on_posix(tmp_path):
    import os
    import stat
    if os.name != "posix":
        return
    database = tmp_path / "private-control.sqlite3"
    # Trigger the database connection against a dedicated app path.
    app = create_app(
        database_path=database,
        admin_token="molido-test-admin-token-32chars-minimum",
        signing_key_pem=ec.generate_private_key(ec.SECP256R1()).private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ),
    )
    TestClient(app).get("/health/ready")
    assert stat.S_IMODE(database.stat().st_mode) == 0o600


def test_non_finite_configuration_numbers_are_rejected(tmp_path):
    client, _ = setup_client(tmp_path)
    admin = {"Authorization": "Bearer molido-test-admin-token-32chars-minimum"}
    credentials = client.post("/v1/devices/enroll", headers=admin, json={"label": "phone"}).json()
    response = client.put(
        f"/v1/devices/{credentials['device_id']}/configuration",
        headers={**admin, "Content-Type": "application/json"},
        content=(
            '{"payload":{"unsafe":NaN},"expires_at":'
            + str(int(time.time()) + 600)
            + '}'
        ),
    )
    assert response.status_code == 422



def test_database_directory_must_be_owner_only(tmp_path):
    import os
    if os.name != "posix":
        return
    data_dir = tmp_path / "shared-data"
    data_dir.mkdir(mode=0o700)
    data_dir.chmod(0o755)
    app = create_app(
        database_path=data_dir / "control.sqlite3",
        admin_token="molido-test-admin-token-32chars-minimum",
        signing_key_pem=ec.generate_private_key(ec.SECP256R1()).private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ),
    )
    response = TestClient(app).get("/health/ready")
    assert response.status_code == 503
    assert "mode 0700" in response.json()["detail"]


def test_database_symlink_is_rejected(tmp_path):
    import os
    if os.name != "posix" or not hasattr(os, "O_NOFOLLOW"):
        return
    data_dir = tmp_path / "private-data"
    data_dir.mkdir(mode=0o700)
    target = tmp_path / "outside.sqlite3"
    target.write_bytes(b"do-not-touch")
    link = data_dir / "control.sqlite3"
    link.symlink_to(target)
    app = create_app(
        database_path=link,
        admin_token="molido-test-admin-token-32chars-minimum",
        signing_key_pem=ec.generate_private_key(ec.SECP256R1()).private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ),
    )
    response = TestClient(app).get("/health/ready")
    assert response.status_code == 503
    assert target.read_bytes() == b"do-not-touch"


def test_database_directory_must_have_exact_owner_mode(tmp_path):
    import os
    if os.name != "posix":
        return
    data_dir = tmp_path / "read-only-data"
    data_dir.mkdir(mode=0o700)
    data_dir.chmod(0o500)
    app = create_app(
        database_path=data_dir / "control.sqlite3",
        admin_token="molido-test-admin-token-32chars-minimum",
        signing_key_pem=ec.generate_private_key(ec.SECP256R1()).private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ),
    )
    response = TestClient(app).get("/health/ready")
    assert response.status_code == 503
    assert "mode 0700" in response.json()["detail"]


def test_configuration_revision_increments_for_each_publish(tmp_path):
    client, _ = setup_client(tmp_path)
    admin = {"Authorization": "Bearer molido-test-admin-token-32chars-minimum"}
    credentials = client.post("/v1/devices/enroll", headers=admin, json={"label": "revision-test"}).json()
    url = f"/v1/devices/{credentials['device_id']}/configuration"
    expires = int(time.time()) + 600

    first = client.put(url, headers=admin, json={"payload": {"policy": "any"}, "expires_at": expires})
    second = client.put(url, headers=admin, json={"payload": {"policy": "wifi-only"}, "expires_at": expires})

    assert first.status_code == 200
    assert first.json()["revision"] == 1
    assert second.status_code == 200
    assert second.json()["revision"] == 2
    fetched = client.get(
        "/v1/configuration",
        headers={"Authorization": f"Bearer {credentials['device_token']}"},
    )
    assert fetched.status_code == 200
    assert fetched.json()["revision"] == 2
