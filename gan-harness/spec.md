# GAN spec — PR Sentinel semaine 1

Source de vérité : `../docs/PLAN-semaine-1.md` (approuvé à la Gate 1).
Stack verrouillée : Python 3.13, FastAPI, psycopg 3, SQL brut, pytest. Auth : GitHub App.

## Comportements attendus (testables)
1. `POST /webhooks/github` rejette (401) toute requête dont `X-Hub-Signature-256` est absente ou invalide ;
   la comparaison est à temps constant et porte sur le corps brut.
2. `ping` → 200. `pull_request` avec `opened|synchronize|reopened` → 202 + une ligne `jobs`.
   Les autres événements et actions → 202 `ignored`. Un payload malformé → 400.
3. Une livraison rejouée (même `X-GitHub-Delivery`) ou le même (repo, PR, sha) ne crée pas de doublon.
   Un nouveau sha met les tâches `queued` de la même PR à l'état `superseded`.
4. Le worker réserve les tâches avec `FOR UPDATE SKIP LOCKED` : deux workers ne reçoivent jamais la même tâche.
   Bail expiré → récupération ; échec → backoff exponentiel ; `dead` après `max_attempts` ;
   un worker dont le bail a été repris ne peut ni terminer ni faire échouer la tâche (jeton de bail).
5. Le worker clone le head sha (le token passe par l'environnement, jamais en argv ni dans l'URL,
   protocoles limités à https), puis lance Semgrep, Gitleaks et Trivy en sous-processus sans shell et avec timeout.
6. Le commentaire est unique par PR (marqueur caché), créé ou mis à jour, et liste les alertes brutes par outil.
   Les entrées non fiables sont échappées (markdown/HTML/@mention), aucun secret n'est publié,
   le corps reste sous 65 536 caractères, et l'échec d'un outil est signalé sans bloquer les autres.
7. Une tâche plus ancienne ne publie pas si une tâche plus récente existe pour la même PR.
