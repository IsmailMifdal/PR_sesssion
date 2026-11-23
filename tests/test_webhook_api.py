import pytest
from fastapi.testclient import TestClient

from pr_sentinel.app import create_app
from pr_sentinel.webhook import sign
from tests.conftest import WEBHOOK_SECRET
from tests.factories import SHA_A, SHA_B, pr_payload, to_body


@pytest.fixture
def client(settings, pool):
    with TestClient(create_app(settings, pool)) as c:
        yield c


def post(client, body: bytes, *, event="pull_request", delivery="d-1", signature=None):
    headers = {
        "X-GitHub-Event": event,
        "X-GitHub-Delivery": delivery,
        "X-Hub-Signature-256": signature or sign(WEBHOOK_SECRET.encode(), body),
        "Content-Type": "application/json",
    }
    return client.post("/webhooks/github", content=body, headers=headers)


def jobs(pool):
    with pool.connection() as conn:
        return conn.execute("SELECT * FROM jobs ORDER BY id").fetchall()


def test_healthz(client):
    assert client.get("/healthz").json() == {"status": "ok"}


def test_unsigned_request_rejected(client, pool):
    body = to_body(pr_payload())
    resp = client.post("/webhooks/github", content=body, headers={"X-GitHub-Event": "pull_request"})
    assert resp.status_code == 401
    assert jobs(pool) == []


def test_bad_signature_rejected(client, pool):
    body = to_body(pr_payload())
    resp = post(client, body, signature=sign(b"other-secret", body))
    assert resp.status_code == 401
    assert jobs(pool) == []


def test_ping(client):
    assert post(client, b"{}", event="ping").json() == {"status": "pong"}


def test_other_event_ignored(client, pool):
    resp = post(client, b"{}", event="push")
    assert resp.status_code == 202 and resp.json()["status"] == "ignored"
    assert jobs(pool) == []


def test_pull_request_queued(client, pool):
    resp = post(client, to_body(pr_payload()))
    assert resp.status_code == 202
    assert resp.json()["status"] == "queued"
    [row] = jobs(pool)
    assert row["status"] == "queued"
    assert (row["repo_full_name"], row["pr_number"], row["head_sha"]) == ("acme/widgets", 7, SHA_A)
    assert row["max_attempts"] == 3


def test_ignored_action(client, pool):
    resp = post(client, to_body(pr_payload(action="closed")))
    assert resp.json()["status"] == "ignored"
    assert jobs(pool) == []


def test_redelivery_is_idempotent(client, pool):
    body = to_body(pr_payload())
    assert post(client, body, delivery="d-1").json()["status"] == "queued"
    assert post(client, body, delivery="d-1").json()["status"] == "duplicate"
    assert post(client, body, delivery="d-2").json()["status"] == "duplicate"  # même sha
    assert len(jobs(pool)) == 1


def test_new_push_supersedes_queued_job(client, pool):
    post(client, to_body(pr_payload(sha=SHA_A)), delivery="d-1")
    post(client, to_body(pr_payload(action="synchronize", sha=SHA_B)), delivery="d-2")
    rows = jobs(pool)
    assert [(r["head_sha"], r["status"]) for r in rows] == [
        (SHA_A, "superseded"),
        (SHA_B, "queued"),
    ]


def test_other_pr_not_superseded(client, pool):
    post(client, to_body(pr_payload(number=1)), delivery="d-1")
    post(client, to_body(pr_payload(number=2, sha=SHA_B)), delivery="d-2")
    assert {r["status"] for r in jobs(pool)} == {"queued"}


@pytest.mark.parametrize("body", [b"not json", b"\xff\xfe", to_body({"action": "opened"})])
def test_malformed_payload(client, pool, body):
    assert post(client, body).status_code == 400
    assert jobs(pool) == []


def test_oversized_payload(settings, pool):
    small = settings.model_copy(update={"max_payload_bytes": 100})
    with TestClient(create_app(small, pool)) as c:
        body = to_body(pr_payload()) + b" " * 200
        assert post(c, body).status_code == 413
