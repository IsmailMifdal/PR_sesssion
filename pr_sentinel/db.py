"""Accès PostgreSQL : pool de connexions et migrations SQL versionnées."""

from __future__ import annotations

from importlib import resources

import psycopg
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

# Clé arbitraire du verrou consultatif qui sérialise les migrations concurrentes.
_MIGRATION_LOCK_KEY = 0x5E17_1E1


def create_pool(database_url: str, *, min_size: int = 1, max_size: int = 10) -> ConnectionPool:
    pool = ConnectionPool(
        database_url,
        min_size=min_size,
        max_size=max_size,
        kwargs={"row_factory": dict_row},
        check=ConnectionPool.check_connection,  # connexions mortes (redémarrage de Postgres)
        open=False,
    )
    pool.open(wait=True)
    return pool


def _migration_files() -> list[tuple[str, str]]:
    folder = resources.files("pr_sentinel") / "migrations"
    files = sorted(
        (entry.name, entry.read_text(encoding="utf-8"))
        for entry in folder.iterdir()
        if entry.name.endswith(".sql")
    )
    return [(name.removesuffix(".sql"), sql) for name, sql in files]


def migrate(database_url: str) -> list[str]:
    """Applique les migrations manquantes ; renvoie les versions appliquées."""
    applied: list[str] = []
    with psycopg.connect(database_url, autocommit=False) as conn:
        conn.execute("SELECT pg_advisory_xact_lock(%s)", (_MIGRATION_LOCK_KEY,))
        conn.execute(
            "CREATE TABLE IF NOT EXISTS schema_migrations ("
            " version text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())"
        )
        done = {row[0] for row in conn.execute("SELECT version FROM schema_migrations")}
        for version, sql in _migration_files():
            if version in done:
                continue
            conn.execute(sql)  # type: ignore[arg-type]  # fichier SQL de confiance, livré avec le paquet
            conn.execute("INSERT INTO schema_migrations (version) VALUES (%s)", (version,))
            applied.append(version)
        conn.commit()
    return applied
