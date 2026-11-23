"""File de tâches PostgreSQL (``FOR UPDATE SKIP LOCKED``, bail + jeton, backoff, dead-letter)."""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from psycopg import errors
from psycopg_pool import ConnectionPool

MAX_ERROR_CHARS = 2000


@dataclass(frozen=True, slots=True)
class NewJob:
    delivery_id: str
    installation_id: int
    repo_full_name: str
    pr_number: int
    head_sha: str


@dataclass(frozen=True, slots=True)
class Job:
    id: int
    installation_id: int
    repo_full_name: str
    pr_number: int
    head_sha: str
    attempts: int
    max_attempts: int
    lock_token: uuid.UUID
    locked_until: datetime
    generation: int

    @classmethod
    def from_row(cls, row: dict[str, Any]) -> Job:
        return cls(**{name: row[name] for name in cls.__slots__})


@dataclass(frozen=True, slots=True)
class EnqueueResult:
    job_id: int | None  # None : doublon ignoré
    superseded: int


def enqueue(pool: ConnectionPool, job: NewJob, *, max_attempts: int) -> EnqueueResult:
    """Met une tâche en file de façon idempotente.

    - même livraison, ou même sha déjà en file / en cours → doublon ignoré ;
    - sha déjà traité, remplacé ou mort (force-push de retour, ``reopened``) → remis en file ;
    - les tâches en attente plus anciennes de la même PR passent à ``superseded``.
    """
    try:
        with pool.connection() as conn, conn.transaction():
            seen = conn.execute("SELECT 1 FROM jobs WHERE delivery_id = %s", (job.delivery_id,)).fetchone()
            if seen:
                return EnqueueResult(job_id=None, superseded=0)
            row = conn.execute(
                """
                INSERT INTO jobs (delivery_id, installation_id, repo_full_name, pr_number,
                                  head_sha, max_attempts)
                VALUES (%s, %s, %s, %s, %s, %s)
                ON CONFLICT (repo_full_name, pr_number, head_sha) DO UPDATE
                SET status = 'queued', attempts = 0, run_after = now(), last_error = NULL,
                    lock_token = NULL, locked_until = NULL,
                    delivery_id = EXCLUDED.delivery_id,
                    installation_id = EXCLUDED.installation_id,
                    max_attempts = EXCLUDED.max_attempts,
                    generation = nextval('jobs_generation_seq'), updated_at = now()
                WHERE jobs.status IN ('done', 'dead', 'superseded')
                RETURNING id, generation
                """,
                (
                    job.delivery_id,
                    job.installation_id,
                    job.repo_full_name,
                    job.pr_number,
                    job.head_sha,
                    max_attempts,
                ),
            ).fetchone()
            if row is None:
                return EnqueueResult(job_id=None, superseded=0)
            cur = conn.execute(
                """
                UPDATE jobs SET status = 'superseded', updated_at = now()
                WHERE repo_full_name = %s AND pr_number = %s
                  AND status = 'queued' AND generation < %s
                """,
                (job.repo_full_name, job.pr_number, row["generation"]),
            )
            return EnqueueResult(job_id=row["id"], superseded=cur.rowcount)
    except errors.UniqueViolation:  # même livraison reçue deux fois en parallèle
        return EnqueueResult(job_id=None, superseded=0)


def reap_expired(pool: ConnectionPool) -> int:
    """Passe en ``dead`` les tâches dont le bail a expiré et qui ont épuisé leurs tentatives."""
    with pool.connection() as conn:
        cur = conn.execute(
            """
            UPDATE jobs
            SET status = 'dead', lock_token = NULL, locked_until = NULL, updated_at = now(),
                last_error = coalesce(last_error, 'bail expiré après la dernière tentative')
            WHERE status = 'running' AND locked_until < now() AND attempts >= max_attempts
            """
        )
        return cur.rowcount


def claim(pool: ConnectionPool, *, lease_seconds: int) -> Job | None:
    """Réserve la prochaine tâche prête (ou dont le bail a expiré) ; ``None`` si la file est vide."""
    with pool.connection() as conn:
        row = conn.execute(
            """
            WITH next AS (
                SELECT id FROM jobs
                WHERE (status = 'queued' AND run_after <= now())
                   OR (status = 'running' AND locked_until < now() AND attempts < max_attempts)
                ORDER BY run_after, id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE jobs j
            SET status = 'running',
                attempts = j.attempts + 1,
                lock_token = %s,
                locked_until = now() + make_interval(secs => %s),
                updated_at = now()
            FROM next
            WHERE j.id = next.id
            RETURNING j.*
            """,
            (uuid.uuid4(), lease_seconds),
        ).fetchone()
    return Job.from_row(row) if row else None


def complete(pool: ConnectionPool, job: Job) -> bool:
    """Marque la tâche terminée ; ``False`` si le bail a été repris par un autre worker."""
    with pool.connection() as conn:
        cur = conn.execute(
            """
            UPDATE jobs
            SET status = 'done', lock_token = NULL, locked_until = NULL,
                last_error = NULL, updated_at = now()
            WHERE id = %s AND status = 'running' AND lock_token = %s
            """,
            (job.id, job.lock_token),
        )
        return cur.rowcount == 1


def backoff_seconds(attempts: int, *, base: int, maximum: int) -> int:
    return min(maximum, base * 2 ** max(0, attempts - 1))


def fail(
    pool: ConnectionPool,
    job: Job,
    error: str,
    *,
    base: int,
    maximum: int,
    permanent: bool = False,
) -> str | None:
    """Replanifie avec backoff, ou passe en ``dead`` (tentatives épuisées ou erreur permanente).

    Renvoie le nouveau statut, ou ``None`` si le bail a été perdu entre-temps.
    """
    delay = backoff_seconds(job.attempts, base=base, maximum=maximum)
    with pool.connection() as conn:
        row = conn.execute(
            """
            UPDATE jobs
            SET status = CASE WHEN %s OR attempts >= max_attempts THEN 'dead' ELSE 'queued' END,
                run_after = now() + make_interval(secs => %s),
                lock_token = NULL, locked_until = NULL,
                last_error = %s, updated_at = now()
            WHERE id = %s AND status = 'running' AND lock_token = %s
            RETURNING status
            """,
            (permanent, delay, error[:MAX_ERROR_CHARS], job.id, job.lock_token),
        ).fetchone()
    return row["status"] if row else None


def has_newer_job(pool: ConnectionPool, job: Job) -> bool:
    """Vrai si une tâche plus récente (et encore utile) existe pour la même PR."""
    with pool.connection() as conn:
        row = conn.execute(
            """
            SELECT EXISTS (
                SELECT 1 FROM jobs
                WHERE repo_full_name = %s AND pr_number = %s AND generation > %s
                  AND status IN ('queued', 'running', 'done')
            ) AS newer
            """,
            (job.repo_full_name, job.pr_number, job.generation),
        ).fetchone()
    return bool(row and row["newer"])
