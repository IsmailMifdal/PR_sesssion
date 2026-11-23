# PR Sentinel

Scan de sécurité automatique des pull requests GitHub. À chaque PR ouverte ou mise à jour,
PR Sentinel lance **Semgrep**, **Gitleaks** et **Trivy** sur le commit de tête et publie
les **alertes brutes** dans un commentaire unique, mis à jour à chaque push.

État : **semaine 1** (voir [`docs/PLAN-semaine-1.md`](docs/PLAN-semaine-1.md)). Pas encore de triage.

```
GitHub ──webhook signé──▶ API (FastAPI) ──INSERT──▶ PostgreSQL (table jobs)
                                                        │ FOR UPDATE SKIP LOCKED
                                                        ▼
               commentaire de PR ◀── GitHub App ◀── worker ── git fetch <sha>
                                                        └─ semgrep · gitleaks · trivy
```

## Fonctionnement

- **Webhook** `POST /webhooks/github` : vérifie `X-Hub-Signature-256` (HMAC-SHA256 sur le corps
  brut, comparaison à temps constant), puis accepte `pull_request` `opened|synchronize|reopened`.
  Rejouer une livraison ou renvoyer le même sha ne crée pas de doublon. Un nouveau push met la tâche
  encore en attente pour la même PR à l'état `superseded`.
- **File PostgreSQL** : table `jobs`. Réservation via `FOR UPDATE SKIP LOCKED`, bail avec jeton
  (un worker dont le bail a expiré ne peut plus écrire), backoff exponentiel, état `dead`
  après `MAX_ATTEMPTS`.
- **Worker** : jeton d'installation GitHub App, clone superficiel du sha exact (le jeton passe par
  l'environnement git, sans hooks, sans liens symboliques, protocole https seul), exécution des
  scanners sans shell, avec timeout et un environnement filtré. L'échec d'un scanner est signalé dans
  le commentaire sans bloquer les autres. Si tous les scanners échouent, la tâche est réessayée.
- **Commentaire** : un seul par PR (marqueur caché), tableaux par outil triés par sévérité,
  texte non fiable échappé (Markdown, HTML, @mentions), **secrets jamais affichés**, taille
  limitée sous 65 536 caractères.

## Démarrage

1. Créer une GitHub App : permissions *Pull requests: Read & write* et *Contents: Read*,
   abonnement à l'événement *Pull request*, URL de webhook `https://<hôte>/webhooks/github`,
   secret de webhook.
2. Configurer l'environnement :
   ```bash
   cp .env.example .env
   ```
   ```bash
   mkdir -p secrets && cp ~/Téléchargements/mon-app.private-key.pem secrets/github-app.pem
   ```
3. Lancer :
   ```bash
   docker compose up --build
   ```

Commandes : `pr-sentinel migrate`, `pr-sentinel serve`, `pr-sentinel worker`.
Toutes les variables commencent par `PR_SENTINEL_` (voir `.env.example` et `pr_sentinel/config.py`).

## Développement

```bash
uv sync
```
```bash
TEST_DATABASE_URL=postgresql://sentinel:sentinel@localhost:5432/sentinel_test uv run pytest --cov
```

Les tests utilisent un vrai PostgreSQL. GitHub et les scanners sont simulés, et les rapports en
fixtures viennent de vraies exécutions. `PR_SENTINEL_REAL_SCANNERS=1` active en plus un test avec le
vrai binaire Gitleaks.

## Sécurité (ce qui est en place)

- Les fichiers d'exclusion ou de configuration des scanners présents dans la PR (`.gitleaksignore`,
  `.gitleaks.toml`, `.semgrepignore`, `trivy.yaml`, `.trivyignore`) sont supprimés avant le scan,
  et les scanners reçoivent une configuration de confiance (`--disable-nosem`,
  `--ignore-gitleaks-allow`, `trivy --config "" --ignorefile "" --offline-scan`).
  Ce comportement est vérifié avec les vrais binaires.
- Les sous-processus tournent dans leur propre groupe de processus, tué en entier au timeout.
  Les rapports sont plafonnés à 50 Mo.
- Le jeton d'installation est limité au dépôt de la PR (`contents:read`, `pull_requests:write`),
  invalidé sur 401. Les erreurs 403/404/410/422 ne sont pas réessayées.
- Conteneurs : système de fichiers en lecture seule, `cap_drop: ALL`, `no-new-privileges`,
  `/tmp` sur un tmpfs de 4 Go, chaque service ne reçoit que ses propres secrets.

## Limites connues (semaine 1)

- Le dépôt est scanné en entier au commit de tête, sans filtrage sur le diff ni triage.
- **Isolation des scanners** : ils tournent dans le conteneur du worker, qui détient la clé de
  l'app et l'accès à la base. Une faille d'un scanner exploitée par un dépôt piégé exposerait
  ces secrets. Prévu ensuite : un bac à sable par scan (conteneur éphémère sans secret ni réseau
  interne).
- Pas d'équité entre installations dans la file : un dépôt public très actif peut retarder les autres.
- `p/default` (Semgrep) télécharge les règles depuis semgrep.dev à chaque scan (à figer dans un
  répertoire versionné). Les dépendances transitives de Semgrep ne sont pas épinglées par hash.
- Si la tâche la plus récente d'une PR finit en `dead`, la PR peut rester sans commentaire à jour.
