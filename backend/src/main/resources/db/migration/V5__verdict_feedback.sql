-- Retours des utilisateurs sur les verdicts de l'agent (👍 juste / 👎 faux), un par personne et par alerte.
CREATE TABLE verdict_feedback (
    finding_id   bigint      NOT NULL REFERENCES finding (id) ON DELETE CASCADE,
    auteur_cle   text        NOT NULL,          -- « u:<id utilisateur> » ou « operateur »
    auteur_login text        NOT NULL,
    utile        boolean     NOT NULL,
    commentaire  text        CHECK (char_length(commentaire) <= 500),
    cree_le      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (finding_id, auteur_cle)
);
CREATE INDEX verdict_feedback_utile_idx ON verdict_feedback (utile, cree_le DESC);
