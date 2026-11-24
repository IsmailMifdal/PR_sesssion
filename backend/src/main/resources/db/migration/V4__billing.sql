-- Facturation (Stripe) : un abonnement par installation GitHub (compte ou organisation).

-- Installations de l'app accessibles à l'utilisateur, relevées à chaque connexion (qui peut gérer quoi).
CREATE TABLE user_installation (
    user_id                bigint NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    github_installation_id bigint NOT NULL,
    compte                 text   NOT NULL,
    PRIMARY KEY (user_id, github_installation_id)
);

-- État de l'abonnement, recopié de Stripe (source de vérité) à chaque événement de webhook.
CREATE TABLE subscription (
    github_installation_id bigint      PRIMARY KEY,
    stripe_customer_id     text        NOT NULL,
    stripe_subscription_id text        NOT NULL UNIQUE,
    statut                 text        NOT NULL,
    sieges                 integer     NOT NULL DEFAULT 1,
    fin_periode            timestamptz,
    annulation_fin_periode boolean     NOT NULL DEFAULT false,
    maj_le                 timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX review_debut_idx ON review (debut);
