-- Modèle de données de PR Sentinel (voir docs/PLAN-pr-sentinel.md).

CREATE TABLE installation (
    id                     bigserial   PRIMARY KEY,
    github_installation_id bigint      NOT NULL UNIQUE,
    compte                 text,
    cree_le                timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE repository (
    id              bigserial PRIMARY KEY,
    installation_id bigint    NOT NULL REFERENCES installation (id) ON DELETE CASCADE,
    nom_complet     text      NOT NULL UNIQUE,
    actif           boolean   NOT NULL DEFAULT true
);

CREATE TABLE review (
    id             bigserial     PRIMARY KEY,
    repository_id  bigint        NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    pr_numero      integer       NOT NULL CHECK (pr_numero > 0),
    commit_sha     text          NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    statut         text          NOT NULL DEFAULT 'EN_COURS'
                   CHECK (statut IN ('EN_COURS', 'TERMINEE', 'ECHEC', 'IGNOREE')),
    debut          timestamptz   NOT NULL DEFAULT now(),
    fin            timestamptz,
    tokens_entree  integer       NOT NULL DEFAULT 0,
    tokens_sortie  integer       NOT NULL DEFAULT 0,
    cout_usd       numeric(12,6) NOT NULL DEFAULT 0,
    -- Résumé publié (et état du check run) : utile au tableau de bord.
    bloquante      boolean       NOT NULL DEFAULT false,
    erreur         text
);
CREATE INDEX review_repository_idx ON review (repository_id, debut DESC);

CREATE TABLE finding (
    id           bigserial PRIMARY KEY,
    review_id    bigint    NOT NULL REFERENCES review (id) ON DELETE CASCADE,
    outil        text      NOT NULL,
    regle        text      NOT NULL,
    fichier      text      NOT NULL,
    ligne        integer,
    gravite_outil text     NOT NULL,
    message      text      NOT NULL DEFAULT '',
    -- Empreinte de l'extrait signalé : jamais le secret lui-même.
    extrait_hash text
);
CREATE INDEX finding_review_idx ON finding (review_id);

CREATE TABLE verdict (
    id          bigserial    PRIMARY KEY,
    finding_id  bigint       NOT NULL UNIQUE REFERENCES finding (id) ON DELETE CASCADE,
    decision    text         NOT NULL CHECK (decision IN ('VRAI_PROBLEME', 'FAUX_POSITIF', 'A_VERIFIER')),
    gravite     text         NOT NULL CHECK (gravite IN ('CRITIQUE', 'HAUTE', 'MOYENNE', 'BASSE')),
    explication text         NOT NULL,
    correctif   text,
    confiance   numeric(4,3) NOT NULL CHECK (confiance BETWEEN 0 AND 1),
    modele      text         NOT NULL
);

-- File de tâches : une ligne par (dépôt, PR, sha). generation = ordre d'arrivée,
-- renouvelé quand un sha déjà vu est remis en file.
CREATE SEQUENCE scan_job_generation_seq;

CREATE TABLE scan_job (
    id              bigserial   PRIMARY KEY,
    delivery_id     text        NOT NULL UNIQUE,
    installation_id bigint      NOT NULL,
    repo_full_name  text        NOT NULL,
    pr_number       integer     NOT NULL CHECK (pr_number > 0),
    head_sha        text        NOT NULL CHECK (head_sha ~ '^[0-9a-f]{40}$'),
    base_sha        text        CHECK (base_sha ~ '^[0-9a-f]{40}$'),
    status          text        NOT NULL DEFAULT 'queued'
                    CHECK (status IN ('queued', 'running', 'done', 'dead', 'superseded')),
    attempts        integer     NOT NULL DEFAULT 0,
    max_attempts    integer     NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
    run_after       timestamptz NOT NULL DEFAULT now(),
    lock_token      uuid,
    locked_until    timestamptz,
    last_error      text,
    generation      bigint      NOT NULL DEFAULT nextval('scan_job_generation_seq'),
    received_at     timestamptz NOT NULL DEFAULT now(),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT scan_job_pr_sha_uniq UNIQUE (repo_full_name, pr_number, head_sha)
);
CREATE INDEX scan_job_queued_idx  ON scan_job (run_after, id) WHERE status = 'queued';
CREATE INDEX scan_job_running_idx ON scan_job (locked_until)  WHERE status = 'running';
CREATE INDEX scan_job_pr_idx      ON scan_job (repo_full_name, pr_number, generation);
