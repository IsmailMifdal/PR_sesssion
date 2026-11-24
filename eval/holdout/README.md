# Cas tenus à l'écart

10 PR (6 vraies failles, 4 pièges) écrites après la mise au point du prompt sur `eval/cases`, et
figées avant tout passage de l'agent (commit dédié). Règle : ces cas servent à mesurer, jamais à
ajuster le prompt. Après un réglage qui s'en inspirerait, ils ne mesureraient plus la généralisation :
il faudrait en écrire de nouveaux.

Les cas ne recoupent pas les formes du banc : requête native JPA, XXE, Zip Slip, client TLS qui
accepte tout, jeton Slack, commons-text 1.9 ; pièges SHA-1 d'identifiant de contenu, SQL sur nom
d'énumération, redirection interne validée par expression régulière, Dockerfile de fixture de test.

Lancer (3 passages pour mesurer la variabilité du modèle) :

    cd backend && for i in 1 2 3; do mvn -q compile exec:java \
      -Dexec.args="--cases ../eval/holdout --out ../eval/report-holdout-$i.json --min-detection 0"; done
