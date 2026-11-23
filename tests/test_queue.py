import threading

from pr_sentinel import queue
from pr_sentinel.queue import NewJob
from tests.factories import SHA_A, SHA_B


def new_job(delivery="d-1", sha=SHA_A, number=7) -> NewJob:
    return NewJob(delivery, 99, "acme/widgets", number, sha)


def row(pool, job_id):
    with pool.connection() as conn:
        return conn.execute("SELECT * FROM jobs WHERE id = %s", (job_id,)).fetchone()


def expire_lease(pool, job_id):
    with pool.connection() as conn:
        conn.execute("UPDATE jobs SET locked_until = now() - interval '1 second' WHERE id = %s", (job_id,))


def make_ready(pool, job_id):
    with pool.connection() as conn:
        conn.execute("UPDATE jobs SET run_after = now() WHERE id = %s", (job_id,))


def test_claim_empty_queue(pool):
    assert queue.claim(pool, lease_seconds=60) is None


def test_claim_marks_running(pool):
    job_id = queue.enqueue(pool, new_job(), max_attempts=3).job_id
    job = queue.claim(pool, lease_seconds=60)
    assert job is not None and job.id == job_id and job.attempts == 1
    r = row(pool, job_id)
    assert r["status"] == "running" and r["lock_token"] == job.lock_token
    assert queue.claim(pool, lease_seconds=60) is None  # déjà réservée


def test_concurrent_workers_never_share_a_job(pool):
    for i in range(20):
        queue.enqueue(pool, new_job(f"d-{i}", sha=f"{i:040x}", number=i + 1), max_attempts=3)
    claimed: list[int] = []
    lock = threading.Lock()
    barrier = threading.Barrier(5)

    def worker():
        barrier.wait()
        while (job := queue.claim(pool, lease_seconds=60)) is not None:
            with lock:
                claimed.append(job.id)

    threads = [threading.Thread(target=worker) for _ in range(5)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert sorted(claimed) == list(range(1, 21))


def test_complete(pool):
    queue.enqueue(pool, new_job(), max_attempts=3)
    job = queue.claim(pool, lease_seconds=60)
    assert queue.complete(pool, job)
    r = row(pool, job.id)
    assert r["status"] == "done" and r["lock_token"] is None


def test_fail_requeues_with_backoff(pool):
    queue.enqueue(pool, new_job(), max_attempts=3)
    job = queue.claim(pool, lease_seconds=60)
    assert queue.fail(pool, job, "boom", base=10, maximum=100) == "queued"
    r = row(pool, job.id)
    assert r["status"] == "queued" and r["last_error"] == "boom"
    with pool.connection() as conn:
        delay = conn.execute("SELECT extract(epoch FROM run_after - now()) AS d FROM jobs").fetchone()
    assert 8 < delay["d"] <= 10
    assert queue.claim(pool, lease_seconds=60) is None  # pas avant le backoff


def test_fail_goes_dead_after_max_attempts(pool):
    job_id = queue.enqueue(pool, new_job(), max_attempts=2).job_id
    for expected in ("queued", "dead"):
        make_ready(pool, job_id)
        job = queue.claim(pool, lease_seconds=60)
        assert queue.fail(pool, job, "boom", base=1, maximum=1) == expected
    assert row(pool, job_id)["status"] == "dead"
    make_ready(pool, job_id)
    assert queue.claim(pool, lease_seconds=60) is None


def test_error_message_truncated(pool):
    queue.enqueue(pool, new_job(), max_attempts=3)
    job = queue.claim(pool, lease_seconds=60)
    queue.fail(pool, job, "x" * 10_000, base=1, maximum=1)
    assert len(row(pool, job.id)["last_error"]) == queue.MAX_ERROR_CHARS


def test_expired_lease_is_reclaimed_and_old_worker_fenced(pool):
    queue.enqueue(pool, new_job(), max_attempts=3)
    first = queue.claim(pool, lease_seconds=60)
    expire_lease(pool, first.id)
    second = queue.claim(pool, lease_seconds=60)
    assert second.id == first.id and second.attempts == 2
    assert second.lock_token != first.lock_token
    # Le premier worker, dont le bail a été repris, ne peut plus rien écrire.
    assert not queue.complete(pool, first)
    assert queue.fail(pool, first, "late", base=1, maximum=1) is None
    assert queue.complete(pool, second)


def test_reap_marks_exhausted_expired_jobs_dead(pool):
    queue.enqueue(pool, new_job(), max_attempts=1)
    job = queue.claim(pool, lease_seconds=60)
    expire_lease(pool, job.id)
    assert queue.claim(pool, lease_seconds=60) is None
    assert queue.reap_expired(pool) == 1
    assert row(pool, job.id)["status"] == "dead"


def test_backoff_is_exponential_and_capped():
    assert [queue.backoff_seconds(n, base=10, maximum=100) for n in range(1, 6)] == [
        10,
        20,
        40,
        80,
        100,
    ]


def test_has_newer_job(pool):
    queue.enqueue(pool, new_job("d-1", SHA_A), max_attempts=3)
    old = queue.claim(pool, lease_seconds=60)
    assert not queue.has_newer_job(pool, old)
    queue.enqueue(pool, new_job("d-2", SHA_B), max_attempts=3)
    assert queue.has_newer_job(pool, old)


def test_running_job_not_superseded(pool):
    queue.enqueue(pool, new_job("d-1", SHA_A), max_attempts=3)
    running = queue.claim(pool, lease_seconds=60)
    result = queue.enqueue(pool, new_job("d-2", SHA_B), max_attempts=3)
    assert result.superseded == 0
    assert row(pool, running.id)["status"] == "running"


# --- Régressions issues de la revue ------------------------------------------
def statuses(pool):
    with pool.connection() as conn:
        return [(r["head_sha"], r["status"]) for r in conn.execute("SELECT * FROM jobs ORDER BY id")]


def test_supersede_never_hits_a_newer_job(pool):
    """Webhooks concurrents : B (plus récent) est validé avant A. A ne doit pas remplacer B."""
    queue.enqueue(pool, new_job("d-b", SHA_B), max_attempts=3)
    with pool.connection() as conn:  # B a obtenu une génération postérieure à celle de A
        conn.execute("UPDATE jobs SET generation = 1000000 WHERE head_sha = %s", (SHA_B,))
    result = queue.enqueue(pool, new_job("d-a", SHA_A), max_attempts=3)
    assert result.superseded == 0
    assert statuses(pool) == [(SHA_B, "queued"), (SHA_A, "queued")]


def test_force_push_back_to_superseded_sha_requeues_it(pool):
    first = queue.enqueue(pool, new_job("d-1", SHA_A), max_attempts=3).job_id
    queue.enqueue(pool, new_job("d-2", SHA_B), max_attempts=3)
    again = queue.enqueue(pool, new_job("d-3", SHA_A), max_attempts=3)
    assert again.job_id == first and again.superseded == 1
    assert statuses(pool) == [(SHA_A, "queued"), (SHA_B, "superseded")]
    job = queue.claim(pool, lease_seconds=60)
    assert job.head_sha == SHA_A and job.attempts == 1


def test_reopened_dead_job_is_rescanned_with_fresh_attempts(pool):
    queue.enqueue(pool, new_job("d-1"), max_attempts=1)
    job = queue.claim(pool, lease_seconds=60)
    assert queue.fail(pool, job, "boom", base=1, maximum=1) == "dead"
    assert queue.enqueue(pool, new_job("d-2"), max_attempts=3).job_id == job.id
    r = row(pool, job.id)
    assert (r["status"], r["attempts"], r["last_error"], r["delivery_id"]) == ("queued", 0, None, "d-2")


def test_same_sha_while_queued_or_running_is_duplicate(pool):
    queue.enqueue(pool, new_job("d-1"), max_attempts=3)
    assert queue.enqueue(pool, new_job("d-2"), max_attempts=3).job_id is None
    queue.claim(pool, lease_seconds=60)
    assert queue.enqueue(pool, new_job("d-3"), max_attempts=3).job_id is None


def test_concurrent_identical_deliveries_insert_once(pool):
    results = []
    barrier = threading.Barrier(4)

    def deliver():
        barrier.wait()
        results.append(queue.enqueue(pool, new_job("same"), max_attempts=3).job_id)

    threads = [threading.Thread(target=deliver) for _ in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert sum(r is not None for r in results) == 1
    assert len(statuses(pool)) == 1


def test_permanent_failure_goes_dead_immediately(pool):
    queue.enqueue(pool, new_job(), max_attempts=5)
    job = queue.claim(pool, lease_seconds=60)
    assert queue.fail(pool, job, "404", base=1, maximum=1, permanent=True) == "dead"
