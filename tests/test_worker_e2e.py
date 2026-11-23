"""Bout en bout : webhook signé → file PostgreSQL → worker → commentaire de PR."""

import functools
import threading
from dataclasses import dataclass
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from pr_sentinel import queue
from pr_sentinel.app import create_app
from pr_sentinel.checkout import checkout
from pr_sentinel.comment import MARKER
from pr_sentinel.github import GitHubApp, GitHubError
from pr_sentinel.scanners.base import Finding, ScanResult
from pr_sentinel.webhook import sign
from pr_sentinel.worker import Worker
from tests.conftest import WEBHOOK_SECRET
from tests.factories import pr_payload, to_body
from tests.test_checkout import git
from tests.test_github import API, FakeGitHub, keypair  # noqa: F401 - fixture


@dataclass(frozen=True)
class GrepScanner:
    """Scanner factice : signale chaque ligne contenant ``needle`` dans le code cloné."""

    name: str
    needle: str

    def scan(self, source: Path, workdir: Path) -> ScanResult:
        findings = tuple(
            Finding(self.name, "needle", "HIGH", p.relative_to(source).as_posix(), i, line.strip())
            for p in sorted(source.rglob("*.py"))
            for i, line in enumerate(p.read_text().splitlines(), 1)
            if self.needle in line
        )
        return ScanResult(self.name, findings)


@dataclass(frozen=True)
class BrokenScanner:
    name: str = "broken"

    def scan(self, source: Path, workdir: Path) -> ScanResult:
        raise RuntimeError("bug")


@pytest.fixture
def env(tmp_path, settings, pool, keypair):  # noqa: F811
    repo = tmp_path / "git" / "acme" / "widgets.git"
    repo.mkdir(parents=True)
    git(repo, "init", "--quiet", "-b", "main")
    (repo / "app.py").write_text("import os\nos.system(cmd)  # DANGER\n")
    git(repo, "add", ".")
    git(repo, "commit", "--quiet", "-m", "pr")
    sha = git(repo, "rev-parse", "HEAD")

    cfg = settings.model_copy(update={"github_git_url": f"file://{tmp_path / 'git'}"})
    fake = FakeGitHub(expires_at="2099-01-01T00:00:00Z")
    github = GitHubApp(42, keypair[0], api_url=API, http=httpx.Client(transport=httpx.MockTransport(fake)))
    worker = Worker(
        pool,
        github,
        [GrepScanner("semgrep", "DANGER"), BrokenScanner()],
        cfg,
        checkout_fn=functools.partial(checkout, allowed_protocols="file"),
    )
    client = TestClient(create_app(cfg, pool))
    return {"repo": repo, "sha": sha, "fake": fake, "worker": worker, "client": client, "pool": pool}


def send(client, payload, delivery):
    body = to_body(payload)
    return client.post(
        "/webhooks/github",
        content=body,
        headers={
            "X-GitHub-Event": "pull_request",
            "X-GitHub-Delivery": delivery,
            "X-Hub-Signature-256": sign(WEBHOOK_SECRET.encode(), body),
        },
    )


def status_of(pool):
    with pool.connection() as conn:
        return [
            (r["status"], r["attempts"], r["last_error"])
            for r in conn.execute("SELECT * FROM jobs ORDER BY id")
        ]


def test_webhook_to_comment(env):
    resp = send(env["client"], pr_payload(sha=env["sha"]), "d-1")
    assert resp.json()["status"] == "queued"

    assert env["worker"].run_once() is True
    assert env["worker"].run_once() is False  # file vide

    [comment] = env["fake"].comments
    body = comment["body"]
    assert body.startswith(MARKER)
    assert env["sha"][:12] in body
    assert "| HIGH | `needle` | `app.py:2` |" in body
    assert "| broken | ⚠️ échec : erreur interne \\(RuntimeError\\) |" in body
    assert status_of(env["pool"]) == [("done", 1, None)]
    posted = [r for r in env["fake"].requests if r.method == "POST" and "issues" in r.url.path]
    assert posted[0].headers["Authorization"] == "Bearer ghs_inst_1"


def test_second_push_updates_same_comment(env):
    send(env["client"], pr_payload(sha=env["sha"]), "d-1")
    env["worker"].run_once()
    (env["repo"] / "app.py").write_text("print('ok')\n")
    git(env["repo"], "commit", "--quiet", "-am", "fix")
    new_sha = git(env["repo"], "rev-parse", "HEAD")
    send(env["client"], pr_payload(action="synchronize", sha=new_sha), "d-2")
    env["worker"].run_once()

    [comment] = env["fake"].comments
    assert new_sha[:12] in comment["body"]
    assert "needle" not in comment["body"]
    assert any(r.method == "PATCH" for r in env["fake"].requests)


def test_github_failure_requeues_with_backoff(env):
    send(env["client"], pr_payload(sha=env["sha"]), "d-1")
    env["fake"].comments = None  # provoque une erreur 500 côté faux GitHub
    env["worker"].run_once()
    [(status, attempts, error)] = status_of(env["pool"])
    assert (status, attempts) == ("queued", 1)
    assert "TypeError" in error or "GitHubError" in error


def test_all_scanners_failing_is_retried(env):
    env["worker"].scanners = [BrokenScanner()]
    send(env["client"], pr_payload(sha=env["sha"]), "d-1")
    env["worker"].run_once()
    [(status, _, error)] = status_of(env["pool"])
    assert status == "queued" and error.startswith("AllScannersFailed")
    assert env["fake"].comments == []


def test_checkout_failure_is_retried(env):
    send(env["client"], pr_payload(sha="f" * 40), "d-1")
    env["worker"].run_once()
    [(status, _, error)] = status_of(env["pool"])
    assert status == "queued" and error.startswith("CheckoutError")


def test_stale_job_does_not_post(env):
    send(env["client"], pr_payload(sha=env["sha"]), "d-1")
    job = queue.claim(env["pool"], lease_seconds=60)
    send(env["client"], pr_payload(action="synchronize", sha="c" * 40), "d-2")
    env["worker"].process(job)
    assert env["fake"].comments == []


def test_run_forever_stops(env):
    stop = threading.Event()
    calls = []

    def fake_run_once():
        calls.append(1)
        if len(calls) == 1:
            raise RuntimeError("db down")  # la boucle survit
        stop.set()
        return False

    env["worker"].run_once = fake_run_once
    env["worker"].run_forever(stop)
    assert len(calls) == 2


def test_not_found_on_github_is_permanent(env):
    send(env["client"], pr_payload(sha=env["sha"]), "d-1")

    def gone(*args, **kwargs):
        raise GitHubError("POST /repos : HTTP 404", status=404)

    env["worker"].github.upsert_report_comment = gone
    env["worker"].run_once()
    [(status, attempts, _)] = status_of(env["pool"])
    assert (status, attempts) == ("dead", 1)


def test_unauthorized_invalidates_cached_token(env):
    send(env["client"], pr_payload(sha=env["sha"]), "d-1")
    github = env["worker"].github

    def unauthorized(*args, **kwargs):
        raise GitHubError("HTTP 401", status=401)

    github.upsert_report_comment = unauthorized
    env["worker"].run_once()
    assert github._tokens == {}
    [(status, _, _)] = status_of(env["pool"])
    assert status == "queued"


def test_stale_job_skipped_before_scanning(env):
    send(env["client"], pr_payload(sha=env["sha"]), "d-1")
    job = queue.claim(env["pool"], lease_seconds=60)
    send(env["client"], pr_payload(action="synchronize", sha="c" * 40), "d-2")
    env["worker"].checkout_fn = lambda *a, **k: pytest.fail("ne doit pas cloner")
    env["worker"].process(job)
    assert env["fake"].comments == []


def test_repo_scanner_overrides_removed_before_scan(env):
    (env["repo"] / ".semgrepignore").write_text("app.py\n")
    git(env["repo"], "add", ".")
    git(env["repo"], "commit", "--quiet", "-m", "ignore")
    sha = git(env["repo"], "rev-parse", "HEAD")
    seen = []

    @dataclass(frozen=True)
    class Spy:
        name: str = "spy"

        def scan(self, source, workdir):
            seen.extend(p.name for p in source.iterdir())
            return ScanResult(self.name)

    env["worker"].scanners = [Spy()]
    send(env["client"], pr_payload(sha=sha), "d-1")
    env["worker"].run_once()
    assert "app.py" in seen and ".semgrepignore" not in seen
