-- Notifications par compte GitHub (installation) : Slack (webhook entrant, chiffré) et rapport hebdomadaire.
CREATE TABLE notification_settings (
    github_installation_id bigint      PRIMARY KEY,
    slack_webhook_chiffre  text,                    -- AES-256-GCM (SENTINEL_ENCRYPTION_KEY), base64
    slack_indice           text,                    -- 4 derniers caractères, pour l'affichage
    slack_niveau           text        NOT NULL DEFAULT 'CRITIQUE'
                           CHECK (slack_niveau IN ('CRITIQUE', 'VRAI_PROBLEME')),
    emails                 text[]      NOT NULL DEFAULT '{}' CHECK (cardinality(emails) <= 10),
    rapport_hebdo          boolean     NOT NULL DEFAULT true,
    maj_le                 timestamptz NOT NULL DEFAULT now(),
    maj_par                text
);

-- Un rapport par compte et par semaine, même si plusieurs instances exécutent la tâche planifiée.
CREATE TABLE weekly_report_sent (
    github_installation_id bigint      NOT NULL,
    semaine                date        NOT NULL,
    envoye_le              timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (github_installation_id, semaine)
);
