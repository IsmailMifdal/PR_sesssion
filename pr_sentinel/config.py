"""Configuration chargée depuis l'environnement (préfixe ``PR_SENTINEL_``)."""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

MIN_WEBHOOK_SECRET_CHARS = 32


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="PR_SENTINEL_", env_file=".env", extra="ignore")

    database_url: str = "postgresql://sentinel:sentinel@localhost:5432/sentinel"

    # Webhook (exigé par l'API seulement ; le worker n'en a pas besoin)
    webhook_secret: SecretStr | None = None
    # Un payload pull_request fait quelques dizaines de Ko ; on refuse bien avant la limite GitHub.
    max_payload_bytes: int = Field(default=2 * 1024 * 1024, gt=0)

    # GitHub App
    github_app_id: int = 0
    github_private_key: SecretStr | None = None
    github_private_key_path: Path | None = None
    github_api_url: str = "https://api.github.com"
    github_git_url: str = "https://github.com"

    # Scanners
    semgrep_config: str = "p/default"
    semgrep_bin: str = "semgrep"
    gitleaks_bin: str = "gitleaks"
    trivy_bin: str = "trivy"
    scanner_timeout_seconds: int = Field(default=300, gt=0)
    clone_timeout_seconds: int = Field(default=120, gt=0)

    # File de tâches
    lease_seconds: int = Field(default=1800, gt=0)
    max_attempts: int = Field(default=5, ge=1)
    backoff_base_seconds: int = Field(default=30, ge=1)
    backoff_max_seconds: int = Field(default=3600, ge=1)
    poll_interval_seconds: float = Field(default=2.0, gt=0)

    @model_validator(mode="after")
    def _strong_webhook_secret(self) -> Settings:
        if self.webhook_secret is not None:
            value = self.webhook_secret.get_secret_value()
            if len(value) < MIN_WEBHOOK_SECRET_CHARS or "change-me" in value.lower():
                raise ValueError(
                    f"webhook_secret doit être aléatoire et faire au moins "
                    f"{MIN_WEBHOOK_SECRET_CHARS} caractères (ex. : openssl rand -hex 32)"
                )
        return self

    @model_validator(mode="after")
    def _lease_covers_job(self) -> Settings:
        # +30 s : marge accordée à Trivy en plus de son propre --timeout
        # 4 commandes git (init, remote, fetch, checkout) bornées chacune par clone_timeout.
        worst_case = 4 * self.clone_timeout_seconds + 3 * (self.scanner_timeout_seconds + 30)
        if self.lease_seconds <= worst_case:
            raise ValueError(
                f"lease_seconds ({self.lease_seconds}) doit dépasser la durée maximale "
                f"d'une tâche ({worst_case}s = clone + 3 scanners)"
            )
        return self

    def webhook_secret_bytes(self) -> bytes:
        if self.webhook_secret is None:
            raise ValueError("PR_SENTINEL_WEBHOOK_SECRET est requis pour l'API")
        return self.webhook_secret.get_secret_value().encode()

    def private_key_pem(self) -> str:
        if self.github_private_key is not None:
            return self.github_private_key.get_secret_value()
        if self.github_private_key_path is not None:
            return self.github_private_key_path.read_text()
        raise ValueError("PR_SENTINEL_GITHUB_PRIVATE_KEY ou _PATH est requis")


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
