import json
import os
import stat
import sys
from pathlib import Path

import pytest

from pr_sentinel.config import Settings
from pr_sentinel.scanners import default_scanners, gitleaks, semgrep, trivy
from pr_sentinel.scanners.base import ScannerError, relative_path, scrubbed_env
from pr_sentinel.scanners.gitleaks import GitleaksScanner
from pr_sentinel.scanners.semgrep import SemgrepScanner
from pr_sentinel.scanners.trivy import TrivyScanner

FIXTURES = Path(__file__).parent / "fixtures"


def fixture(name):
    return json.loads((FIXTURES / name).read_text())


# --- Analyse des rapports réels ---------------------------------------------
def test_parse_semgrep_fixture(tmp_path):
    [f] = semgrep.parse_report(fixture("semgrep.json"), tmp_path)
    assert (f.tool, f.rule_id, f.severity, f.path, f.line) == (
        "semgrep",
        "subprocess-shell-true",
        "ERROR",
        "app.py",
        3,
    )
    assert "shell=True" in f.message


def test_parse_gitleaks_fixture_never_keeps_secret(tmp_path):
    findings = gitleaks.parse_report(fixture("gitleaks.json"), tmp_path)
    assert {f.rule_id for f in findings} == {"github-pat", "aws-access-token"}
    assert all(f.severity == "SECRET" and f.path == "config.env" and f.line for f in findings)
    assert "REDACTED" not in repr(findings)


def test_parse_gitleaks_drops_secret_even_without_redaction(tmp_path):
    raw = [{"RuleID": "r", "File": "a", "StartLine": 1, "Secret": "hunter2", "Match": "pw=hunter2"}]
    assert "hunter2" not in repr(gitleaks.parse_report(raw, tmp_path))


def test_parse_gitleaks_null_report(tmp_path):
    assert gitleaks.parse_report(None, tmp_path) == ()


def test_parse_trivy_fixture(tmp_path):
    findings = trivy.parse_report(fixture("trivy.json"), tmp_path)
    vulns = [f for f in findings if f.path == "requirements.txt"]
    misconf = [f for f in findings if f.path == "Dockerfile"]
    assert len(vulns) == 3 and len(misconf) == 2
    assert all(f.rule_id.startswith(("CVE-", "GHSA-")) for f in vulns)
    assert all(f.severity in {"LOW", "MEDIUM", "HIGH", "CRITICAL"} for f in findings)
    assert any("→" in f.message or "pas de correctif" in f.message for f in vulns)


def test_parse_trivy_skips_passed_misconfig(tmp_path):
    data = {
        "Results": [
            {
                "Target": "x",
                "Misconfigurations": [
                    {"ID": "A", "Status": "PASS"},
                    {"ID": "B", "Status": "FAIL", "Severity": "low"},
                ],
            }
        ]
    }
    [f] = trivy.parse_report(data, tmp_path)
    assert (f.rule_id, f.severity) == ("B", "LOW")


@pytest.mark.parametrize(
    ("parser", "bad"),
    [
        (semgrep.parse_report, []),
        (semgrep.parse_report, {"results": "x"}),
        (gitleaks.parse_report, {"not": "a list"}),
        (trivy.parse_report, []),
        (trivy.parse_report, {"Results": "x"}),
    ],
)
def test_unexpected_report_shapes(parser, bad, tmp_path):
    with pytest.raises(ScannerError):
        parser(bad, tmp_path)


def test_parsers_tolerate_garbage_entries(tmp_path):
    assert semgrep.parse_report({"results": [1, {"extra": "x", "start": None}]}, tmp_path)[0].line is None
    assert gitleaks.parse_report([1, {"StartLine": True}], tmp_path)[0].line is None
    assert trivy.parse_report({"Results": [1, {"Vulnerabilities": [2]}]}, tmp_path) == ()


def test_relative_path(tmp_path):
    assert relative_path(str(tmp_path / "a" / "b.py"), tmp_path) == "a/b.py"
    assert relative_path("/etc/passwd", tmp_path) == "passwd"
    assert relative_path("./x/y", tmp_path) == "x/y"


# --- Exécution en sous-processus (faux binaires) -----------------------------
FAKE = """#!{python}
import os, shutil, sys, time
args = sys.argv[1:]
mode = {mode!r}
if mode == "sleep":
    time.sleep(5)
if mode == "fail":
    sys.stderr.write("kaboom")
    sys.exit(2)
if mode == "env":
    leaked = [k for k in os.environ if k.startswith("PR_SENTINEL")]
    sys.exit(3 if leaked else 0)
for flag in ("--output", "--report-path"):
    if flag in args:
        out = args[args.index(flag) + 1]
        if mode == "garbage":
            open(out, "w").write("{{not json")
        elif mode != "noreport":
            shutil.copy({fixture!r}, out)
"""


def fake_binary(tmp_path, mode, fixture_name):
    path = tmp_path / f"fake-{mode}-{fixture_name}"
    path.write_text(FAKE.format(python=sys.executable, mode=mode, fixture=str(FIXTURES / fixture_name)))
    path.chmod(path.stat().st_mode | stat.S_IEXEC)
    return str(path)


SCANNERS = [
    (lambda b: SemgrepScanner(config="rules.yml", timeout=2, binary=b), "semgrep.json", 1),
    (lambda b: GitleaksScanner(timeout=2, binary=b), "gitleaks.json", 2),
    (lambda b: TrivyScanner(timeout=2, binary=b), "trivy.json", 5),
]


@pytest.mark.parametrize(("make", "fixture_name", "count"), SCANNERS)
def test_scanner_success(tmp_path, make, fixture_name, count):
    result = make(fake_binary(tmp_path, "ok", fixture_name)).scan(tmp_path, tmp_path)
    assert result.ok and len(result.findings) == count


@pytest.mark.parametrize(("make", "fixture_name", "count"), SCANNERS)
@pytest.mark.parametrize(
    ("mode", "error"),
    [("fail", "code de sortie 2"), ("noreport", "rapport absent"), ("garbage", "rapport JSON illisible")],
)
def test_scanner_failures_are_reported_not_raised(tmp_path, make, fixture_name, count, mode, error):
    result = make(fake_binary(tmp_path, mode, fixture_name)).scan(tmp_path, tmp_path)
    assert not result.ok and result.error == error and result.findings == ()


def test_scanner_timeout(tmp_path):
    scanner = GitleaksScanner(timeout=1, binary=fake_binary(tmp_path, "sleep", "gitleaks.json"))
    assert scanner.scan(tmp_path, tmp_path).error == "délai dépassé (1s)"


def test_missing_binary(tmp_path):
    result = GitleaksScanner(timeout=1, binary=str(tmp_path / "nope")).scan(tmp_path, tmp_path)
    assert result.error.startswith("exécutable introuvable")


def test_worker_secrets_not_passed_to_scanners(tmp_path, monkeypatch):
    monkeypatch.setenv("PR_SENTINEL_WEBHOOK_SECRET", "leak-me")
    assert "PR_SENTINEL_WEBHOOK_SECRET" not in scrubbed_env()
    scanner = GitleaksScanner(timeout=2, binary=fake_binary(tmp_path, "env", "gitleaks.json"))
    assert scanner.scan(tmp_path, tmp_path).error == "rapport absent"  # code 0 → pas de fuite


def test_commands_do_not_use_shell_and_target_cwd(tmp_path):
    cmd = SemgrepScanner(config="p/default", timeout=1).command(tmp_path / "r.json")
    assert cmd.argv[0] == "semgrep" and cmd.argv[-1] == "."
    assert "--metrics=off" in cmd.argv
    assert "--disable-nosem" in cmd.argv
    gl = GitleaksScanner(timeout=1).command(tmp_path / "r.json", tmp_path / "ign").argv
    assert "--redact" in gl and "--ignore-gitleaks-allow" in gl
    trusted = gl[gl.index("--config") + 1]
    assert trusted.endswith("gitleaks.toml") and "useDefault = true" in open(trusted).read()
    assert gl[gl.index("--gitleaks-ignore-path") + 1] == str(tmp_path / "ign")
    tv = TrivyScanner(timeout=1).command(tmp_path / "r.json").argv
    assert "--offline-scan" in tv
    assert tv[tv.index("--config") + 1] == "" and tv[tv.index("--ignorefile") + 1] == ""


def test_default_scanners():
    settings = Settings(semgrep_config="rules.yml")
    names = [s.name for s in default_scanners(settings)]
    assert names == ["semgrep", "gitleaks", "trivy"]


def test_oversized_report_rejected(tmp_path, monkeypatch):
    from pr_sentinel.scanners import base

    monkeypatch.setattr(base, "MAX_REPORT_BYTES", 10)
    (tmp_path / "r.json").write_text("[" + "1," * 20 + "1]")
    with pytest.raises(ScannerError, match="trop volumineux"):
        base.load_json_report(tmp_path / "r.json")


REAL = pytest.mark.skipif(
    os.environ.get("PR_SENTINEL_REAL_SCANNERS") != "1",
    reason="PR_SENTINEL_REAL_SCANNERS=1 pour lancer les vrais binaires",
)


@REAL
def test_real_gitleaks_ignores_inline_allow_and_repo_config(tmp_path):
    (tmp_path / "src").mkdir()
    (tmp_path / "src" / "c.env").write_text('k = "AKIAIOSFODNN7EXAMPLQ" # gitleaks:allow\n')
    (tmp_path / "src" / ".gitleaks.toml").write_text("[allowlist]\npaths = ['.*']\n")
    result = GitleaksScanner(timeout=60).scan(tmp_path / "src", tmp_path)
    assert [f.rule_id for f in result.findings] == ["aws-access-token"]


@REAL
def test_real_gitleaks_on_sample(tmp_path):
    (tmp_path / "src").mkdir()
    (tmp_path / "src" / "c.env").write_text('token = "ghp_R2d2C3poR2d2C3poR2d2C3poR2d2C3po1234"\n')
    result = GitleaksScanner(timeout=60).scan(tmp_path / "src", tmp_path)
    assert result.ok and [f.rule_id for f in result.findings] == ["github-pat"]
    assert "ghp_" not in repr(result)
