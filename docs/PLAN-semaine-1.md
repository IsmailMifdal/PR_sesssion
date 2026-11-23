# PR Sentinel — Semaine 1 : plan en tranches verticales

## Périmètre (extrait de la brève)

1. Webhook GitHub **signé** (vérification `X-Hub-Signature-256`, HMAC-SHA256, comparaison à temps constant).
2. **File de tâches PostgreSQL** (pas de Redis/RabbitMQ) : `SELECT … FOR UPDATE SKIP LOCKED`.
3. **Scan** du code de la PR avec **Semgrep**, **Gitleaks**, **Trivy** (fs).
4. **Commentaire** sur la PR listant les **alertes brutes** (sans triage ni déduplication intelligente, ni LLM).

Hors périmètre semaine 1 : triage/priorisation, filtrage sur le diff, dashboard, multi-tenant, autofix.

## Décisions verrouillées (proposées)

| Sujet | Choix proposé | Raison |
|---|---|---|
| Langage / API | Python 3.13 + FastAPI | Semgrep est en Python ; écosystème de tests mature |
| Accès DB | psycopg 3 + SQL brut, migrations `.sql` versionnées | file de tâches = quelques requêtes SQL précises, pas besoin d'ORM |
| File | table `jobs` + `FOR UPDATE SKIP LOCKED`, bail (lease) + réessais avec backoff, état `dead` après N tentatives | robuste, pas d'infra en plus |
| Idempotence | unicité sur `X-GitHub-Delivery` + sur (repo, PR, head_sha) | GitHub renvoie des livraisons en double |
| Événements | `pull_request` : `opened`, `synchronize`, `reopened` | le reste → 202 ignoré |
| Auth GitHub | **GitHub App** (JWT RS256 → token d'installation) | l'`installation.id` arrive dans le payload ; tokens courts ; une alternative PAT est possible |
| Récupération du code | `git clone --depth 1` du head SHA dans un répertoire temporaire, supprimé après le scan | aucun code de la PR n'est **exécuté** (analyse statique seulement) |
| Commentaire | **un seul** commentaire par PR, mis à jour (marqueur HTML caché), tronqué sous 65 536 caractères | évite le spam à chaque push |
| Tests | pytest ; Postgres réel (cluster local / docker-compose) ; GitHub et scanners simulés par des fakes | |
| Déploiement local | `docker-compose` (postgres + api + worker), image avec les 3 scanners épinglés | |

## Tranches (ordre d'exécution)

### Tranche 0 — Scaffold (`feat: scaffold`)
- `pyproject.toml` (uv), arborescence `pr_sentinel/`, config via variables d'env (pydantic-settings), `GET /healthz`.
- Migration `001_jobs.sql`, petit outil de migration, `docker-compose.yml`, `.env.example`, CI pytest.

### Tranche 1 — Chemin complet bout en bout, un seul scanner (`feat: signed webhook → queue → semgrep → PR comment`)
- `POST /webhooks/github` : signature valide, sinon 401 ; filtre d'événements ; mise en file idempotente ; réponse 202.
- Worker : réserve une tâche, clone le head SHA, lance **Semgrep** (`--json`), normalise vers `Finding`, publie le commentaire.
- Client GitHub App (JWT → token d'installation, création/mise à jour du commentaire).
- Test e2e : payload signé → ligne `jobs` → worker → le faux GitHub reçoit le commentaire attendu.

### Tranche 2 — Gitleaks + Trivy (`feat: gitleaks and trivy scanners`)
- Adaptateurs `gitleaks detect --no-git --report-format json` et `trivy fs --format json` (vulnérabilités + mauvaises configurations).
- Échec partiel : si un scanner plante ou dépasse son délai, le commentaire le signale et les autres résultats sont quand même publiés.
- Les **secrets détectés par Gitleaks sont masqués** dans le commentaire (fichier:ligne + règle seulement).

### Tranche 3 — Robustesse de la file (`feat: queue retries, leases and dead-letter`)
- Expiration du bail → tâche récupérée ; backoff exponentiel ; `dead` après `MAX_ATTEMPTS`.
- Un nouveau push (`synchronize`) remplace une tâche en attente pour la même PR.
- Dockerfile avec Semgrep/Gitleaks/Trivy en versions épinglées.

## Boucle de build
Pour chaque tranche : TDD (`tdd-guide`) → boucle `/gan-build --skip-planner --eval-mode code-only`
(les critères sont dans `gan-harness/eval-rubric.md`) → `python-reviewer` + **`security-reviewer`**
(webhook HMAC, secrets, SQL, sous-processus, appels API) → **Gate 2** → un commit `feat:` par tranche.
