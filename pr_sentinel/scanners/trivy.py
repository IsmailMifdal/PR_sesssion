"""Adaptateur Trivy (vulnérabilités des dépendances et mauvaises configurations)."""

from __future__ import annotations

from dataclasses import dataclass
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
class TrivyScanner:
    timeout: int
    binary: str = "trivy"
    name: str = "trivy"

    def command(self, report: Path) -> CommandSpec:
        return CommandSpec(
            argv=[
                self.binary,
                "fs",
                # Ni trivy.yaml ni .trivyignore de la PR ; aucune requête réseau pilotée par le dépôt.
                "--config",
                "",
                "--ignorefile",
                "",
                "--offline-scan",
                "--format",
                "json",
                "--output",
                str(report),
                "--scanners",
                "vuln,misconfig",
                "--exit-code",
                "0",
                "--quiet",
                "--timeout",
                f"{self.timeout}s",
                ".",
            ],
        )

    def scan(self, source: Path, workdir: Path) -> ScanResult:
        report = workdir / "trivy.json"
        try:
            run_command(self.name, self.command(report), cwd=source, timeout=self.timeout + 30)
            findings = parse_report(load_json_report(report), source)
        except ScannerError as exc:
            return ScanResult(tool=self.name, error=str(exc))
        return ScanResult(tool=self.name, findings=findings)


def _vulnerability(target: str, vuln: dict) -> Finding:
    pkg = as_str(vuln.get("PkgName"), "?")
    installed = as_str(vuln.get("InstalledVersion"), "?")
    fixed = as_str(vuln.get("FixedVersion"))
    title = as_str(vuln.get("Title"))
    message = f"{pkg} {installed}" + (f" → {fixed}" if fixed else " (pas de correctif)")
    if title:
        message += f" : {title}"
    return Finding(
        tool="trivy",
        rule_id=as_str(vuln.get("VulnerabilityID"), "?"),
        severity=as_str(vuln.get("Severity"), "UNKNOWN").upper(),
        path=target,
        line=None,
        message=message,
    )


def _misconfiguration(target: str, misconf: dict) -> Finding:
    cause = misconf.get("CauseMetadata") if isinstance(misconf.get("CauseMetadata"), dict) else {}
    return Finding(
        tool="trivy",
        rule_id=as_str(misconf.get("ID"), "?"),
        severity=as_str(misconf.get("Severity"), "UNKNOWN").upper(),
        path=target,
        line=as_int(cause.get("StartLine")),
        message=as_str(misconf.get("Title")),
    )


def parse_report(data: object, source: Path) -> tuple[Finding, ...]:
    if not isinstance(data, dict):
        raise ScannerError("format de rapport Trivy inattendu")
    results = data.get("Results") or []
    if not isinstance(results, list):
        raise ScannerError("format de rapport Trivy inattendu")
    findings: list[Finding] = []
    for result in results:
        if not isinstance(result, dict):
            continue
        target = relative_path(as_str(result.get("Target")), source)
        for vuln in result.get("Vulnerabilities") or []:
            if isinstance(vuln, dict):
                findings.append(_vulnerability(target, vuln))
        for misconf in result.get("Misconfigurations") or []:
            if isinstance(misconf, dict) and misconf.get("Status", "FAIL") == "FAIL":
                findings.append(_misconfiguration(target, misconf))
    return tuple(findings)
