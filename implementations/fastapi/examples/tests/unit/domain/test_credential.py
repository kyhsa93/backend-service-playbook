from src.auth.domain.credential import Credential


def test_create_시_credential_id와_생성_시각이_자동으로_채워진다() -> None:
    credential = Credential.create(user_id="owner-1", password_hash="hashed-password")

    assert credential.credential_id
    assert credential.user_id == "owner-1"
    assert credential.password_hash == "hashed-password"
    assert credential.created_at is not None
