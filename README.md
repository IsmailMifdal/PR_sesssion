# PR Sentinel

Agent IA qui relit chaque pull request sous l'angle de la sécurité. Il lance **Semgrep**,
**Gitleaks** et **Trivy**, garde les alertes des lignes modifiées, puis un agent **Spring AI** trie les
vrais problèmes des fausses alertes, explique le risque en deux phrases et propose un correctif
applicable en un clic, directement dans la PR. Un *check run* bloque la fusion tant qu'une faille
critique reste ouverte.

Java 21 · Spring Boot 3.5 · Spring AI 1.1 · PostgreSQL (Flyway) · Angular 22 · GitHub App

```
GitHub ──webhook signé──▶ Spring Boot /webhooks/github ──▶ PostgreSQL (scan_job, SKIP LOCKED)
                                                              │
   revue + suggestions ◀── GitHub App ◀── worker ── clone du commit ── Semgrep · Gitleaks · Trivy
   check run                                │                              │
                                            │        filtre : lignes modifiées par la PR
                                            ▼                              │
                         PostgreSQL ◀── verdicts JSON ◀── agent IA (lireFichier · lireDiff · chercher)
                              │
                              ▼
                     tableau de bord Angular
```

## Ce que fait une revue

1. **Réception** : signature `X-Hub-Signature-256` vérifiée à temps constant, tâche enregistrée en base,
   réponse en quelques millisecondes. Une livraison rejouée ne crée pas de doublon ; un nouveau push
   remplace la tâche en attente.
2. **Scan** : clone superficiel du commit exact (jeton hors argv et hors URL, ni hooks, ni liens
   symboliques, ni sous-modules), puis les trois scanners avec délai maximal. Les fichiers par lesquels
   une PR pourrait désactiver un scanner (`.gitleaksignore`, `.semgrepignore`, `trivy.yaml`…) sont
   supprimés avant l'analyse, et les commentaires `nosemgrep` / `gitleaks:allow` sont ignorés.
3. **Filtrage** : seules les alertes sur les lignes ajoutées par la PR (API `pulls/{n}/files`) sont
   gardées, plus les dépendances vulnérables des fichiers modifiés.
4. **Triage IA** : pour chaque alerte (30 au plus, les plus graves d'abord), l'agent lit le contexte,
   appelle au plus 5 outils et rend un verdict structuré :
   ```java
   record Verdict(Decision decision,   // VRAI_PROBLEME, FAUX_POSITIF, A_VERIFIER
                  Gravite gravite,     // CRITIQUE, HAUTE, MOYENNE, BASSE
                  String explication,  // 2 phrases max
                  String correctif,    // remplacement proposé, ou null
                  double confiance) {}
   ```
   Une réponse hors format est rejetée et relancée une fois. `gpt-4o-mini` par défaut ; second avis de
   `gpt-4o` quand une alerte est jugée critique.
5. **Publication** : revue GitHub avec résumé, commentaires sur les lignes et blocs `suggestion` ; les
   faux positifs écartés restent consultables dans un bloc replié. Sans clé OpenAI, ou une fois le budget
   du jour atteint, PR Sentinel publie les alertes brutes.

| Revues | Détail d'une PR | Métriques |
|---|---|---|
| ![Liste des revues](docs/captures/revues.png) | ![Détail d'une PR](docs/captures/detail-pr.png) | ![Métriques](docs/captures/metriques.png) |

<sub>Captures prises sur des données de démonstration fictives.</sub>

## Sécurité de l'outil lui-même

- **Accès GitHub** : HMAC du webhook vérifié à chaque requête ; jeton d'installation limité au seul
  dépôt de la PR et aux permissions `contents: read`, `pull_requests: write`, `checks: write`,
  `metadata: read` ; clé privée uniquement en variable d'environnement.
- **Code analysé = donnée non fiable** : jamais exécuté ; le prompt place le code entre délimiteurs que
  le contenu ne peut pas imiter et précise qu'il s'agit de données, ce qui neutralise les « ignore les
  alertes » glissés dans un commentaire (cas `20-prompt-injection` du banc). Les secrets sont masqués
  avant tout envoi au modèle ; seule leur empreinte SHA-256 est stockée.
- **Exécution** : scanners sans shell, environnement filtré, arbre de processus tué au délai maximal,
  rapports plafonnés ; base Trivy intégrée à l'image (aucun téléchargement pendant l'analyse) ;
  dossier de travail supprimé après chaque revue ; conteneurs en lecture seule, sans capacités.
- **Coûts et abus** : plafond de dépense par jour et par dépôt, 30 alertes au plus envoyées au modèle.
- **Tableau de bord** : jeton Bearer (32 caractères minimum) comparé à temps constant, CORS limité,
  CSP stricte côté front ; l'API `/api` est désactivée sans jeton.
- **Pipeline** : tests JUnit + Testcontainers, SonarCloud (si configuré), image Docker signée avec
  cosign, Dependabot.

## Évaluation

Le banc `eval/cases` contient 20 PR dont on connaît la bonne réponse : 12 vraies failles (injection
SQL, XSS, commande shell, path traversal, SSRF, désérialisation, JWT sans signature, CSRF désactivé,
redirection ouverte, MD5 pour les mots de passe, secret commité, log4j 2.14.1) et 8 pièges que les
scanners signalent alors que le code est sûr (fausse clé de test, SQL/commande/redirection sur liste
blanche, MD5 de cache, `Random` d'affichage, CSRF d'une API sans état, commentaire d'injection de prompt).

```bash
cd backend && mvn -q compile exec:java -Dexec.args="--cases ../eval/cases --out ../eval/report.json"
```

Le workflow `eval.yml` relance le banc à chaque modification du prompt, de l'agent ou des cas, et
échoue si la détection descend sous 11/12 : le prompt est testé comme du code.

**Mesuré à ce jour : scanners seuls, sans agent** (`eval/report-scanners-seuls.json`, Semgrep avec
les règles Java du dépôt `semgrep-rules`, Gitleaks 8.30, Trivy 0.76). C'est la référence que l'agent
doit améliorer : garder les 12 failles en écartant le bruit.

| Métrique | Scanners seuls (mesuré) | Objectif avec l'agent |
|---|---|---|
| Failles signalées | 12/12 | ≥ 11/12 confirmées en `VRAI_PROBLEME` |
| Fausses alertes sur les 8 pièges | 11 alertes, 0 écartée | ≥ 70 % écartées |
| Fausses alertes parmi les alertes publiées | 35,5 % (11 sur 31) | ≤ 15 % |
| Temps de scan par PR | 7,2 s | < 90 s de bout en bout |
| Coût par PR | 0 $ | < 0,01 $ |

Les chiffres **avec l'agent** ne sont pas encore mesurés : il faut lancer le banc avec
`OPENAI_API_KEY` (localement ou via le secret du dépôt pour `eval.yml`). Ne citer que des résultats
mesurés.

## Installation

1. **GitHub App** : permissions *Contents* (lecture), *Pull requests* (écriture), *Checks* (écriture),
   *Metadata* (lecture) ; événement *Pull request* ; URL de webhook `https://<api>/webhooks/github` ;
   secret de webhook (`openssl rand -hex 32`). Télécharger la clé privée.
2. **Local** :
   ```bash
   cp .env.example .env
   ```
   ```bash
   docker compose up --build
   ```
3. **Railway** (API, worker et PostgreSQL) : `railway.json` construit `docker/Dockerfile` ; deux services
   depuis la même image (`SENTINEL_WORKER_ENABLED=false` pour l'API, `true` pour le worker), variables
   de `.env.example`.
4. **Vercel** (front) : répertoire `frontend/`, `vercel.json` fourni ; définir
   `SENTINEL_DASHBOARD_ALLOWED_ORIGINS` côté API avec l'URL Vercel. L'API doit être servie en HTTPS (CSP).
5. **Banc réel** : `eval/push-bench.sh <clone du dépôt de banc>` ouvre les 20 cas en vraies PR sur un
   dépôt où l'app est installée.

## Développement

```bash
cd backend && mvn verify
```
```bash
cd frontend && npm ci && npx ng test --watch=false && npx ng build
```

Les tests d'intégration utilisent Testcontainers ; sans Docker, définir
`TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/<base>`.

```
backend/   Spring Boot : webhook/, scan/, diff/, agent/, github/, review/ (+ api/ du tableau de bord), eval/
frontend/  Angular : revues, détail d'une PR, métriques
eval/      20 cas, script de publication en vraies PR
docker/    Dockerfile (JDK 21 + Semgrep + Gitleaks + Trivy, base intégrée)
.github/   ci.yml, eval.yml, release.yml, dependabot.yml
```
