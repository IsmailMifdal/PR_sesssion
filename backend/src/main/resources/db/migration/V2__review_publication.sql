-- Publication idempotente : une revue publiée sur GitHub n'est jamais republiée par un nouvel essai.
ALTER TABLE review ADD COLUMN publiee boolean NOT NULL DEFAULT false;
CREATE INDEX review_pr_commit_idx ON review (repository_id, pr_numero, commit_sha) WHERE publiee;
-- Revues orphelines (worker arrêté en pleine revue) : repérées puis passées en ECHEC.
CREATE INDEX review_en_cours_idx ON review (debut) WHERE statut = 'EN_COURS';
