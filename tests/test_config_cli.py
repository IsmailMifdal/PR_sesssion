import pytest
from pydantic import ValidationError

from pr_sentinel import cli
from pr_sentinel.config import Settings
from pr_sentinel.db import migrate


@pytest.mark.parametrize("secret", ["short", "x" * 31, "change-me-to-a-long-random-string-0123"])
def test_weak_webhook_secret_rejected(secret):
    with pytest.raises(ValidationError, match="webhook_secret"):
        Settings(webhook_secret=secret)


def test_worker_does_not_need_webhook_secret_but_api_does():
    settings = Settings()
    with pytest.raises(ValueError, match="WEBHOOK_SECRET"):
        settings.webhook_secret_bytes()
    assert Settings(webhook_secret="k" * 32).webhook_secret_bytes() == b"k" * 32


def test_lease_must_cover_worst_case_job():
    with pytest.raises(ValidationError, match="lease_seconds"):
        Settings(lease_seconds=600, scanner_timeout_seconds=300)


def test_private_key_sources(tmp_path):
    assert Settings(github_private_key="PEM").private_key_pem() == "PEM"
    key = tmp_path / "key.pem"
    key.write_text("FILE")
    assert Settings(github_private_key_path=key).private_key_pem() == "FILE"
    with pytest.raises(ValueError):
        Settings().private_key_pem()


def test_migrate_is_idempotent(database_url):
    assert migrate(database_url) == []


def test_cli_migrate(monkeypatch, database_url, capsys):
    monkeypatch.setenv("PR_SENTINEL_DATABASE_URL", database_url)
    cli.get_settings.cache_clear()
    try:
        cli.main(["migrate"])
    finally:
        cli.get_settings.cache_clear()
    assert "aucune" in capsys.readouterr().out


def test_cli_requires_command():
    with pytest.raises(SystemExit):
        cli.main([])
