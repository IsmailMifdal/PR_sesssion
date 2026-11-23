"""Rendu Markdown du commentaire de PR (alertes brutes, sans triage)."""

from __future__ import annotations

import re
from collections.abc import Sequence

from pr_sentinel.scanners.base import Finding, ScanResult

MARKER = "<!-- pr-sentinel:report -->"
MAX_COMMENT_CHARS = 65_536
_BUDGET = MAX_COMMENT_CHARS - 2_000  # marge pour l'en-tête et les notes de troncature
MAX_FIELD_CHARS = 300

_SEVERITY_ORDER = {
    "CRITICAL": 0,
    "SECRET": 0,
    "HIGH": 1,
    "ERROR": 1,
    "MEDIUM": 2,
    "WARNING": 2,
    "LOW": 3,
    "INFO": 4,
}
_MD_SPECIAL = re.compile(r"([\\`*_{}\[\]()#+!|~>.:-])")  # . et : : pas d'autolien GFM


def escape(text: str, limit: int = MAX_FIELD_CHARS) -> str:
    """Neutralise le Markdown, le HTML et les @mentions d'un texte non fiable."""
    text = " ".join(text.split())  # pas de saut de ligne dans une cellule de tableau
    if len(text) > limit:
        text = text[: limit - 1] + "…"
    text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    text = _MD_SPECIAL.sub(r"\\\1", text)
    return text.replace("@", "@​")


def code(text: str, limit: int = MAX_FIELD_CHARS) -> str:
    """Span de code inline : ni Markdown, ni HTML, ni mention, ni autolien n'y sont interprétés."""
    text = " ".join(text.split())
    if len(text) > limit:
        text = text[: limit - 1] + "…"
    return "`" + text.replace("`", "'").replace("|", "\\|") + "`"


def _location(finding: Finding) -> str:
    loc = finding.path or "?"
    if finding.line:
        loc += f":{finding.line}"
    return code(loc)


def _row(finding: Finding) -> str:
    return (
        f"| {escape(finding.severity, 20)} | {code(finding.rule_id, 120)} "
        f"| {_location(finding)} | {escape(finding.message)} |"
    )


def _sorted(findings: Sequence[Finding]) -> list[Finding]:
    return sorted(
        findings,
        key=lambda f: (_SEVERITY_ORDER.get(f.severity, 9), f.path, f.line or 0, f.rule_id),
    )


def _status(result: ScanResult) -> str:
    if not result.ok:
        return f"⚠️ échec : {escape(result.error or '', 120)}"
    return "✅ aucune alerte" if not result.findings else f"🔎 {len(result.findings)} alerte(s)"


def render(results: Sequence[ScanResult], *, head_sha: str) -> str:
    total = sum(len(r.findings) for r in results)
    lines = [
        MARKER,
        "## 🛡️ PR Sentinel — alertes brutes",
        "",
        f"Commit analysé : `{head_sha[:12]}` · {total} alerte(s) au total, "
        "**non triées** (résultats bruts des scanners).",
        "",
        "| Outil | Résultat |",
        "|---|---|",
        *(f"| {r.tool} | {_status(r)} |" for r in results),
    ]
    used = sum(len(line) + 1 for line in lines)
    for result in results:
        if not result.findings:
            continue
        section = [
            "",
            f"<details><summary><b>{result.tool}</b> — {len(result.findings)} alerte(s)</summary>",
            "",
            "| Sévérité | Règle | Emplacement | Message |",
            "|---|---|---|---|",
        ]
        rows = [_row(f) for f in _sorted(result.findings)]
        used += sum(len(line) + 1 for line in section) + len("</details>") + 2
        shown = 0
        for row in rows:
            if used + len(row) + 1 > _BUDGET:
                break
            section.append(row)
            used += len(row) + 1
            shown += 1
        if shown < len(rows):
            section.append("")
            section.append(
                f"_… {len(rows) - shown} alerte(s) supplémentaire(s) non affichée(s) "
                "(limite de taille des commentaires GitHub)._"
            )
        section += ["", "</details>"]
        lines += section
    lines += [
        "",
        "<sub>Les secrets détectés sont masqués : seuls la règle et l'emplacement sont indiqués."
        " Ce commentaire est mis à jour à chaque push.</sub>",
    ]
    body = "\n".join(lines)
    return body[:MAX_COMMENT_CHARS]
