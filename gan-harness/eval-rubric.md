# Grille d'évaluation (eval-mode: code-only, seuil 7.0/10)

| Critère | Poids | 10/10 si… |
|---|---|---|
| Correction | 3 | tous les comportements 1–7 de spec.md sont couverts par des tests qui passent |
| Sécurité | 3 | HMAC temps constant ; SQL paramétré ; pas de shell ; token absent d'argv/URL/logs ; secrets masqués ; échappement du commentaire |
| Robustesse | 2 | bail/jeton, backoff, dead-letter, échec partiel des scanners, idempotence |
| Qualité | 1 | modules < 400 lignes, fonctions courtes, types, pas d'état global mutable |
| Couverture | 1 | ≥ 80 % (pytest --cov) |
