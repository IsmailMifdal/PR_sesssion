#!/usr/bin/env bash
# Pousse les 20 cas comme 20 PR sur un dépôt de banc (ex. : moi/pr-sentinel-bench) où la GitHub App
# PR Sentinel est installée. Usage : eval/push-bench.sh <chemin du clone local du dépôt de banc>
# Prérequis : le clone existe et a une branche main ; `gh` est authentifié.
# Les cas 11 et 13 contiennent des clés factices d'aspect réel : la protection des pushs de GitHub peut
# demander une autorisation explicite (lien affiché par git).
set -euo pipefail
bench=${1:?chemin du clone du dépôt de banc}
cases=$(cd "$(dirname "$0")/cases" && pwd)
cd "$bench"
git switch -q main
for dir in "$cases"/*/; do
  id=$(basename "$dir")
  titre=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["titre"])' "$dir/case.json")
  git switch -q main
  git switch -q -C "base/$id"
  git rm -rq --ignore-unmatch . && git clean -fdq
  [ -d "$dir/base" ] && cp -R "$dir/base/." .
  git add -A && git commit -qm "base: $id" --allow-empty
  git push -qf origin "base/$id"
  git switch -q -C "pr/$id"
  cp -R "$dir/pr/." .
  python3 - "$dir/case.json" <<'PY'
import json, pathlib, sys
subs = json.load(open(sys.argv[1])).get("substitutions", {})
for f in pathlib.Path(".").rglob("*"):
    if f.is_file() and ".git" not in f.parts:
        t = f.read_text(errors="ignore")
        r = t
        for k, parts in subs.items():
            r = r.replace(k, "".join(parts))
        if r != t:
            f.write_text(r)
PY
  git add -A && git commit -qm "$titre"
  git push -qf origin "pr/$id"
  gh pr create --base "base/$id" --head "pr/$id" --title "[$id] $titre" --body "Cas du banc PR Sentinel." || true
done
