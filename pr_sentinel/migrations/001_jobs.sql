-- File de tâches de scan : une ligne par (repo, PR, head_sha).
-- generation : ordre d'arrivée, renouvelé quand un sha déjà vu est remis en file.
CREATE SEQUENCE jobs_generation_seq;

CREATE TABLE jobs (
    id              bigserial PRIMARY KEY,
    delivery_id     text        NOT NULL UNIQUE,
    installation_id bigint      NOT NULL,
    repo_full_name  text        NOT NULL,
    pr_number       integer     NOT NULL CHECK (pr_number > 0),
    head_sha        text        NOT NULL CHECK (head_sha ~ '^[0-9a-f]{40}$'),
    status          text        NOT NULL DEFAULT 'queued'
                    CHECK (status IN ('queued', 'running', 'done', 'dead', 'superseded')),
    attempts        integer     NOT NULL DEFAULT 0,
    max_attempts    integer     NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
    run_after       timestamptz NOT NULL DEFAULT now(),
    lock_token      uuid,
    locked_until    timestamptz,
    last_error      text,
    generation      bigint      NOT NULL DEFAULT nextval('jobs_generation_seq'),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT jobs_pr_sha_uniq UNIQUE (repo_full_name, pr_number, head_sha)
);

-- Tâches prêtes à être réservées.
CREATE INDEX jobs_queued_idx ON jobs (run_after, id) WHERE status = 'queued';
-- Tâches en cours dont le bail peut expirer.
CREATE INDEX jobs_running_idx ON jobs (locked_until) WHERE status = 'running';
-- Recherche par PR (supersede, tâche plus récente).
CREATE INDEX jobs_pr_idx ON jobs (repo_full_name, pr_number, generation);
