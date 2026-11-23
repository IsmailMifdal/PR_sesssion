from __future__ import annotations

import os
from collections.abc import Iterator

import psycopg
import pytest
from psycopg_pool import ConnectionPool

from pr_sentinel.config import Settings
from pr_sentinel.db import create_pool, migrate

TEST_DB = os.environ.get("TEST_DATABASE_URL")
WEBHOOK_SECRET = "test-webhook-secret-0123456789abcdef"


@pytest.fixture(scope="session")
def database_url() -> str:
    if not TEST_DB:
        pytest.skip("TEST_DATABASE_URL non défini : tests PostgreSQL ignorés")
    with psycopg.connect(TEST_DB, autocommit=True) as conn:
        conn.execute("DROP TABLE IF EXISTS jobs, schema_migrations")
        conn.execute("DROP SEQUENCE IF EXISTS jobs_generation_seq")
    migrate(TEST_DB)
    return TEST_DB


@pytest.fixture(scope="session")
def _session_pool(database_url: str) -> Iterator[ConnectionPool]:
    pool = create_pool(database_url, max_size=5)
    yield pool
    pool.close()


@pytest.fixture
def pool(_session_pool: ConnectionPool) -> ConnectionPool:
    with _session_pool.connection() as conn:
        conn.execute("TRUNCATE jobs RESTART IDENTITY")
    return _session_pool


@pytest.fixture
def settings(database_url: str) -> Settings:
    return Settings(
        database_url=database_url,
        webhook_secret=WEBHOOK_SECRET,
        github_app_id=42,
        github_api_url="https://api.github.test",
        github_git_url="https://github.test",
        max_attempts=3,
        backoff_base_seconds=10,
        backoff_max_seconds=100,
    )
