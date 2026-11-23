"""API HTTP : réception des webhooks GitHub et mise en file des scans."""

from __future__ import annotations

import json
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from psycopg_pool import ConnectionPool
from starlette.concurrency import run_in_threadpool

from pr_sentinel import queue
from pr_sentinel.config import Settings, get_settings
from pr_sentinel.db import create_pool
from pr_sentinel.webhook import InvalidPayload, parse_pull_request, verify_signature

log = logging.getLogger(__name__)


def _error(status: int, detail: str) -> JSONResponse:
    return JSONResponse(status_code=status, content={"detail": detail})


async def _read_body(request: Request, limit: int) -> bytes | None:
    """Lit le corps brut en refusant tout dépassement de ``limit`` (None si trop gros)."""
    chunks: list[bytes] = []
    size = 0
    async for chunk in request.stream():
        size += len(chunk)
        if size > limit:
            return None
        chunks.append(chunk)
    return b"".join(chunks)


def create_app(settings: Settings | None = None, pool: ConnectionPool | None = None) -> FastAPI:
    settings = settings or get_settings()
    secret = settings.webhook_secret_bytes()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        if pool is not None:  # pool injecté : il appartient à l'appelant
            yield
            return
        app.state.pool = create_pool(settings.database_url)
        try:
            yield
        finally:
            app.state.pool.close()

    app = FastAPI(title="PR Sentinel", lifespan=lifespan, docs_url=None, redoc_url=None)
    if pool is not None:
        app.state.pool = pool

    @app.get("/healthz")
    def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/webhooks/github")
    async def github_webhook(request: Request) -> JSONResponse:
        declared = request.headers.get("content-length")
        if declared and declared.isdigit() and int(declared) > settings.max_payload_bytes:
            return _error(413, "payload trop volumineux")
        body = await _read_body(request, settings.max_payload_bytes)
        if body is None:
            return _error(413, "payload trop volumineux")

        if not verify_signature(secret, body, request.headers.get("x-hub-signature-256")):
            log.warning("webhook rejeté : signature invalide")
            return _error(401, "signature invalide")

        event = request.headers.get("x-github-event", "")
        if event == "ping":
            return JSONResponse(status_code=200, content={"status": "pong"})
        if event != "pull_request":
            return JSONResponse(status_code=202, content={"status": "ignored"})

        try:
            payload = json.loads(body)
            new_job = parse_pull_request(request.headers.get("x-github-delivery", ""), payload)
        except (json.JSONDecodeError, UnicodeDecodeError, InvalidPayload) as exc:
            return _error(400, f"payload invalide : {exc}")
        if new_job is None:
            return JSONResponse(status_code=202, content={"status": "ignored"})

        result = await run_in_threadpool(
            queue.enqueue, request.app.state.pool, new_job, max_attempts=settings.max_attempts
        )
        if result.job_id is None:
            return JSONResponse(status_code=202, content={"status": "duplicate"})
        log.info(
            "tâche %s en file pour %s#%s @ %s",
            result.job_id,
            new_job.repo_full_name,
            new_job.pr_number,
            new_job.head_sha[:12],
        )
        return JSONResponse(status_code=202, content={"status": "queued", "job_id": result.job_id})

    return app
