-- Connexion des utilisateurs par GitHub (OAuth de la GitHub App).
-- Les jetons (codes de connexion, sessions) ne sont stockés que sous forme d'empreinte SHA-256.

CREATE TABLE app_user (
    id                 bigserial   PRIMARY KEY,
    github_id          bigint      NOT NULL UNIQUE,
    login              text        NOT NULL,
    nom                text,
    avatar_url         text,
    cree_le            timestamptz NOT NULL DEFAULT now(),
    derniere_connexion timestamptz NOT NULL DEFAULT now()
);

-- Dépôts visibles par l'utilisateur : ceux où l'app est installée et auxquels il a accès sur GitHub,
-- relevés à chaque connexion.
CREATE TABLE user_repo (
    user_id bigint NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    depot   text   NOT NULL,
    PRIMARY KEY (user_id, depot)
);

CREATE TABLE login_code (
    empreinte bytea       PRIMARY KEY,
    user_id   bigint      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    expire_le timestamptz NOT NULL,
    utilise   boolean     NOT NULL DEFAULT false
);

CREATE TABLE user_session (
    empreinte bytea       PRIMARY KEY,
    user_id   bigint      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    cree_le   timestamptz NOT NULL DEFAULT now(),
    expire_le timestamptz NOT NULL
);
CREATE INDEX user_session_expire_idx ON user_session (expire_le);
CREATE INDEX login_code_expire_idx ON login_code (expire_le);
