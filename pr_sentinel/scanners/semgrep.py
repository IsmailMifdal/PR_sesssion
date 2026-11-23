"""Adaptateur Semgrep (analyse statique)."""

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
class SemgrepScanner:
    config: str
    timeout: int
    binary: str = "semgrep"
    name: str = "semgrep"

    def command(self, report: Path) -> CommandSpec:
        return CommandSpec(
            argv=[
                self.binary,
                "scan",
                "--config",
                self.config,
                "--json",
                "--output",
                str(report),
                "--metrics=off",
                "--disable-nosem",  # les commentaires nosemgrep de la PR ne masquent rien
                "--disable-version-check",
                "--quiet",
                ".",
            ],
            # 0 : succès (avec ou sans alertes, car --error n'est pas passé).
            ok_exit_codes=frozenset({0}),
        )

    def scan(self, source: Path, workdir: Path) -> ScanResult:
        report = workdir / "semgrep.json"
        try:
            run_command(self.name, self.command(report), cwd=source, timeout=self.timeout)
            findings = parse_report(load_json_report(report), source)
        except ScannerError as exc:
            return ScanResult(tool=self.name, error=str(exc))
        return ScanResult(tool=self.name, findings=findings)


def parse_report(data: object, source: Path) -> tuple[Finding, ...]:
    if not isinstance(data, dict) or not isinstance(data.get("results"), list):
        raise ScannerError("format de rapport Semgrep inattendu")
    findings = []
    for item in data["results"]:
        if not isinstance(item, dict):
            continue
        extra = item.get("extra") if isinstance(item.get("extra"), dict) else {}
        start = item.get("start") if isinstance(item.get("start"), dict) else {}
        findings.append(
            Finding(
                tool="semgrep",
                rule_id=as_str(item.get("check_id"), "?"),
                severity=as_str(extra.get("severity"), "UNKNOWN").upper(),
                path=relative_path(as_str(item.get("path")), source),
                line=as_int(start.get("line")),
                message=as_str(extra.get("message")),
            )
        )
    return tuple(findings)
