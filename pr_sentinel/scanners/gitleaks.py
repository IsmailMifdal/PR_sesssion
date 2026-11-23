"""Adaptateur Gitleaks (détection de secrets). Le secret lui-même n'est jamais conservé."""

from __future__ import annotations

from dataclasses import dataclass
from importlib import resources
from pathlib import Path

from pr_sentinel.scanners.base import (
    CommandSpec,
    Finding,
    ScannerError,
    ScanResult,
    as_int,
    as_str,
    load_json_report,
    relative_path,
    run_command,
)


@dataclass(frozen=True, slots=True)
class GitleaksScanner:
    timeout: int
    binary: str = "gitleaks"
    name: str = "gitleaks"

    def command(self, report: Path, ignore_dir: Path) -> CommandSpec:
        trusted_config = resources.files("pr_sentinel.scanners") / "gitleaks.toml"
        return CommandSpec(
            argv=[
                self.binary,
                "dir",
                ".",
                # Configuration et liste d'exclusion de confiance : celles de la PR sont ignorées.
                "--config",
                str(trusted_config),
                "--gitleaks-ignore-path",
                str(ignore_dir),
                "--ignore-gitleaks-allow",
                "--report-format",
                "json",
                "--report-path",
                str(report),
                "--redact",
                "--no-banner",
                "--exit-code",
                "0",
                "--log-level",
                "warn",
            ],
        )

    def scan(self, source: Path, workdir: Path) -> ScanResult:
        report = workdir / "gitleaks.json"
        ignore_dir = workdir / "gitleaks-ignore"
        ignore_dir.mkdir(exist_ok=True)
        try:
            run_command(self.name, self.command(report, ignore_dir), cwd=source, timeout=self.timeout)
            findings = parse_report(load_json_report(report), source)
        except ScannerError as exc:
            return ScanResult(tool=self.name, error=str(exc))
        return ScanResult(tool=self.name, findings=findings)


def parse_report(data: object, source: Path) -> tuple[Finding, ...]:
    if data is None:
        return ()
    if not isinstance(data, list):
        raise ScannerError("format de rapport Gitleaks inattendu")
    findings = []
    for item in data:
        if not isinstance(item, dict):
            continue
        rule = as_str(item.get("RuleID"), "?")
        # Seuls la règle et l'emplacement sont gardés : ni Secret, ni Match, ni Line.
        findings.append(
            Finding(
                tool="gitleaks",
                rule_id=rule,
                severity="SECRET",
                path=relative_path(as_str(item.get("File")), source),
                line=as_int(item.get("StartLine")),
                message=as_str(item.get("Description"), rule),
            )
        )
    return tuple(findings)
