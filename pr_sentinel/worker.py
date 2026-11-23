"""Worker : réserve les tâches, clone la PR, lance les scanners, publie le commentaire."""

from __future__ import annotations

import logging
import signal
import tempfile
import threading
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from pathlib import Path

from psycopg_pool import ConnectionPool

from pr_sentinel import queue
from pr_sentinel.checkout import checkout
from pr_sentinel.comment import render
from pr_sentinel.config import Settings
from pr_sentinel.github import GitHubApp, GitHubError
from pr_sentinel.scanners import Scanner, ScanResult

log = logging.getLogger(__name__)

CheckoutFn = Callable[..., Path]


class AllScannersFailed(RuntimeError):
    pass


@dataclass
class Worker:
    pool: ConnectionPool
    github: GitHubApp
    scanners: Sequence[Scanner]
    settings: Settings
    checkout_fn: CheckoutFn = checkout

    # --- Une tâche --------------------------------------------------------
    def process(self, job: queue.Job) -> None:
        if queue.has_newer_job(self.pool, job):
            log.info("tâche %s dépassée par un push plus récent : ignorée", job.id)
            return
        token = self.github.installation_token(job.installation_id, job.repo_full_name)
        clone_url = f"{self.settings.github_git_url.rstrip('/')}/{job.repo_full_name}.git"
        with tempfile.TemporaryDirectory(prefix="pr-sentinel-") as tmp:
            source = self.checkout_fn(
                clone_url,
                job.head_sha,
                Path(tmp) / "src",
                token=token,
                timeout=self.settings.clone_timeout_seconds,
            )
            reports = Path(tmp) / "reports"
            reports.mkdir()
            results = [self._run_scanner(s, source, reports) for s in self.scanners]
        if results and all(not r.ok for r in results):
            raise AllScannersFailed("; ".join(f"{r.tool}: {r.error}" for r in results))
        if queue.has_newer_job(self.pool, job):
            log.info("tâche %s dépassée par un push plus récent : pas de commentaire", job.id)
            return
        body = render(results, head_sha=job.head_sha)
        # Jeton relu : le scan a pu durer assez longtemps pour qu'il approche de l'expiration.
        token = self.github.installation_token(job.installation_id, job.repo_full_name)
        self.github.upsert_report_comment(token, job.repo_full_name, job.pr_number, body)

    @staticmethod
    def _run_scanner(scanner: Scanner, source: Path, reports: Path) -> ScanResult:
        try:
            result = scanner.scan(source, reports)
        except Exception as exc:  # un scanner défaillant ne doit pas bloquer les autres
            log.exception("scanner %s : erreur inattendue", scanner.name)
            return ScanResult(tool=scanner.name, error=f"erreur interne ({type(exc).__name__})")
        log.info(
            "%s : %s alerte(s)%s",
            result.tool,
            len(result.findings),
            f", erreur : {result.error}" if result.error else "",
        )
        return result

    # --- Boucle -----------------------------------------------------------
    def run_once(self) -> bool:
        """Traite au plus une tâche ; renvoie ``False`` si la file était vide."""
        queue.reap_expired(self.pool)
        job = queue.claim(self.pool, lease_seconds=self.settings.lease_seconds)
        if job is None:
            return False
        log.info(
            "tâche %s : %s#%s @ %s (tentative %s/%s)",
            job.id,
            job.repo_full_name,
            job.pr_number,
            job.head_sha[:12],
            job.attempts,
            job.max_attempts,
        )
        try:
            self.process(job)
        except Exception as exc:
            permanent = isinstance(exc, GitHubError) and exc.permanent
            if isinstance(exc, GitHubError) and exc.status == 401:
                self.github.invalidate_token(job.installation_id, job.repo_full_name)
            status = queue.fail(
                self.pool,
                job,
                f"{type(exc).__name__}: {exc}",
                base=self.settings.backoff_base_seconds,
                maximum=self.settings.backoff_max_seconds,
                permanent=permanent,
            )
            log.warning("tâche %s en échec (%s) → %s", job.id, exc, status or "bail perdu")
        else:
            if not queue.complete(self.pool, job):
                log.warning("tâche %s : bail perdu avant la fin", job.id)
        return True

    def run_forever(self, stop: threading.Event) -> None:
        while not stop.is_set():
            try:
                worked = self.run_once()
            except Exception:
                log.exception("erreur de la boucle du worker")
                worked = False
            if not worked:
                stop.wait(self.settings.poll_interval_seconds)


def install_signal_handlers(stop: threading.Event) -> None:
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: stop.set())
