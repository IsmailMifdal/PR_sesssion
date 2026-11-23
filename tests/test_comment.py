from pr_sentinel.comment import MARKER, MAX_COMMENT_CHARS, escape, render
from pr_sentinel.scanners.base import Finding, ScanResult

SHA = "0123456789abcdef" * 2 + "01234567"


def finding(**kw):
    base = dict(tool="semgrep", rule_id="r1", severity="ERROR", path="app.py", line=3, message="msg")
    return Finding(**{**base, **kw})


def test_render_summary_and_sections():
    body = render(
        [
            ScanResult("semgrep", (finding(),)),
            ScanResult("gitleaks", ()),
            ScanResult("trivy", error="délai dépassé (300s)"),
        ],
        head_sha=SHA,
    )
    assert body.startswith(MARKER)
    assert "`0123456789ab`" in body
    assert "| semgrep | 🔎 1 alerte(s) |" in body
    assert "| gitleaks | ✅ aucune alerte |" in body
    assert "| trivy | ⚠️ échec : délai dépassé \\(300s\\) |" in body
    assert "| ERROR | `r1` | `app.py:3` | msg |" in body
    assert "<b>gitleaks</b>" not in body  # pas de section vide


def test_findings_sorted_by_severity():
    body = render(
        [
            ScanResult(
                "trivy",
                (
                    finding(severity="LOW", rule_id="low"),
                    finding(severity="CRITICAL", rule_id="crit"),
                    finding(severity="MEDIUM", rule_id="med"),
                ),
            )
        ],
        head_sha=SHA,
    )
    assert body.index("crit") < body.index("med") < body.index("low")


def test_escape_neutralises_markdown_html_and_mentions():
    out = escape("a|b <script>alert(1)</script> @octocat `x` [l](http://e)\nnext")
    assert "|" not in out.replace("\\|", "")
    assert "<script>" not in out and "&lt;script&gt;" in out
    assert "@octocat" not in out and "@​octocat" in out
    assert "\n" not in out
    assert "\\`x\\`" in out and "\\[l\\]" in out


def test_escape_truncates_long_fields():
    assert len(escape("x" * 1000, 50)) == 50


def test_untrusted_path_cannot_break_table():
    body = render(
        [ScanResult("gitleaks", (finding(path="evil|`</details>`@all.py", line=None),))],
        head_sha=SHA,
    )
    # Dans un span de code : pas de HTML ni de mention, et le pipe est échappé.
    assert "| `evil\\|'</details>'@all.py` |" in body


def test_autolinks_neutralised_in_messages():
    out = escape("voir www.evil.example ou https://evil.example")
    assert "www.evil" not in out and "https://" not in out


def test_truncated_under_github_limit():
    many = tuple(finding(rule_id=f"rule-{i}", message="m" * 250) for i in range(2000))
    body = render([ScanResult("semgrep", many)], head_sha=SHA)
    assert len(body) <= MAX_COMMENT_CHARS
    assert "supplémentaire(s) non affichée(s)" in body
    assert body.rstrip().endswith("</sub>")
