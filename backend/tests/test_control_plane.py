import base64, time
from fastapi.testclient import TestClient
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from backend.app.main import create_app

def setup_client(tmp_path):
    key = ec.generate_private_key(ec.SECP256R1())
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    return TestClient(create_app(database_path=tmp_path / "control.sqlite3", admin_token="test-admin-secret", signing_key_pem=pem)), key

def test_enroll_publish_and_fetch_signed_configuration(tmp_path):
    client, key = setup_client(tmp_path)
    admin = {"Authorization": "Bearer test-admin-secret"}
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
    admin = {"Authorization": "Bearer test-admin-secret"}
    credentials = client.post("/v1/devices/enroll", headers=admin, json={"label": "phone"}).json()
    assert client.post(f"/v1/devices/{credentials['device_id']}/revoke", headers=admin).status_code == 200
    response = client.get("/v1/configuration", headers={"Authorization": f"Bearer {credentials['device_token']}"})
    assert response.status_code == 401

def test_invalid_admin_token_is_rejected(tmp_path):
    client, _ = setup_client(tmp_path)
    response = client.post("/v1/devices/enroll", headers={"Authorization": "Bearer wrong"}, json={"label": "phone"})
    assert response.status_code == 401
