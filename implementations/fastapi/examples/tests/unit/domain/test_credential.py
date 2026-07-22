from src.auth.domain.credential import Credential


def test_create_auto_fills_credential_id_and_created_at() -> None:
    credential = Credential.create(user_id="owner-1", password_hash="hashed-password")

    assert credential.credential_id
    assert credential.user_id == "owner-1"
    assert credential.password_hash == "hashed-password"
    assert credential.created_at is not None
