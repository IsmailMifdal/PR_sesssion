"""Scanners de sécurité exécutés sur le code de la PR."""

from pr_sentinel.config import Settings
from pr_sentinel.scanners.base import Finding, Scanner, ScanResult
from pr_sentinel.scanners.gitleaks import GitleaksScanner
from pr_sentinel.scanners.semgrep import SemgrepScanner
from pr_sentinel.scanners.trivy import TrivyScanner

__all__ = ["Finding", "ScanResult", "Scanner", "default_scanners"]


def default_scanners(settings: Settings) -> list[Scanner]:
    timeout = settings.scanner_timeout_seconds
    return [
        SemgrepScanner(config=settings.semgrep_config, timeout=timeout, binary=settings.semgrep_bin),
        GitleaksScanner(timeout=timeout, binary=settings.gitleaks_bin),
        TrivyScanner(timeout=timeout, binary=settings.trivy_bin),
    ]
