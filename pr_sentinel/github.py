"""Client GitHub App : jetons d'installation et commentaire unique de PR."""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass
from datetime import datetime
from typing import Any

import httpx
import jwt

from pr_sentinel.comment import MARKER

_API_VERSION = "2022-11-28"
_TOKEN_SAFETY_MARGIN = 300  # secondes : on renouvelle avant l'expiration réelle
_MAX_COMMENT_PAGES = 30  # 3 000 commentaires : au-delà, on publie un nouveau commentaire
# Permissions minimales du jeton d'installation (limité en plus au seul dépôt de la PR).
TOKEN_PERMISSIONS = {"contents": "read", "pull_requests": "write"}
# Erreurs qu'un nouvel essai ne corrigera pas (app désinstallée, dépôt supprimé, droits manquants).
PERMANENT_STATUSES = frozenset({403, 404, 410, 422})


class GitHubError(RuntimeError):
    def __init__(self, message: str, status: int | None = None) -> None:
        super().__init__(message)
        self.status = status

    @property
    def permanent(self) -> bool:
        return self.status in PERMANENT_STATUSES


@dataclass(frozen=True, slots=True)
class _CachedToken:
    value: str
    expires_at: float


class GitHubApp:
    def __init__(
        self,
        app_id: int,
        private_key_pem: str,
        *,
        api_url: str = "https://api.github.com",
        http: httpx.Client | None = None,
        clock: Any = time.time,
    ) -> None:
        if app_id <= 0:
            raise ValueError("PR_SENTINEL_GITHUB_APP_ID est requis")
        self.app_id = app_id
        self._key = private_key_pem
        self._clock = clock
        self._http = http or httpx.Client(timeout=30)
        self._api = api_url.rstrip("/")
        self._tokens: dict[int, _CachedToken] = {}
        self._lock = threading.Lock()

    # --- Authentification -------------------------------------------------
    def app_jwt(self) -> str:
        now = int(self._clock())
        claims = {"iat": now - 60, "exp": now + 9 * 60, "iss": str(self.app_id)}
        return jwt.encode(claims, self._key, algorithm="RS256")

    def installation_token(self, installation_id: int, repo: str) -> str:
        """Jeton d'installation limité à ``repo`` et aux permissions minimales, mis en cache."""
        key = (installation_id, repo)
        with self._lock:
            cached = self._tokens.get(key)
            if cached and cached.expires_at - _TOKEN_SAFETY_MARGIN > self._clock():
                return cached.value
        resp = self._request(
            "POST",
            f"/app/installations/{installation_id}/access_tokens",
            bearer=self.app_jwt(),
            json={"repositories": [repo.split("/", 1)[1]], "permissions": TOKEN_PERMISSIONS},
        )
        data = resp.json()
        token = data.get("token")
        if not isinstance(token, str) or not token:
            raise GitHubError("réponse de jeton d'installation invalide")
        with self._lock:
            self._tokens[key] = _CachedToken(token, self._expiry(data.get("expires_at")))
        return token

    def invalidate_token(self, installation_id: int, repo: str) -> None:
        with self._lock:
            self._tokens.pop((installation_id, repo), None)

    def _expiry(self, raw: object) -> float:
        if isinstance(raw, str):
            try:
                return datetime.fromisoformat(raw).timestamp()
            except ValueError:
                pass
        return self._clock() + 3600  # durée de vie documentée par GitHub

    # --- Commentaires -----------------------------------------------------
    def find_report_comment(self, token: str, repo: str, pr_number: int) -> int | None:
        url: str | None = f"{self._api}/repos/{repo}/issues/{pr_number}/comments?per_page=100"
        for _ in range(_MAX_COMMENT_PAGES):
            if url is None:
                break
            resp = self._request("GET", url, bearer=token)
            for comment in resp.json():
                app = comment.get("performed_via_github_app") or {}
                if app.get("id") == self.app_id and MARKER in (comment.get("body") or ""):
                    return int(comment["id"])
            url = resp.links.get("next", {}).get("url")
        return None

    def upsert_report_comment(self, token: str, repo: str, pr_number: int, body: str) -> int:
        existing = self.find_report_comment(token, repo, pr_number)
        if existing is not None:
            resp = self._request(
                "PATCH",
                f"/repos/{repo}/issues/comments/{existing}",
                bearer=token,
                json={"body": body},
            )
        else:
            resp = self._request(
                "POST",
                f"/repos/{repo}/issues/{pr_number}/comments",
                bearer=token,
                json={"body": body},
            )
        return int(resp.json()["id"])

    # --- Transport --------------------------------------------------------
    def _request(self, method: str, path_or_url: str, *, bearer: str, **kwargs: Any) -> httpx.Response:
        url = path_or_url if path_or_url.startswith(self._api) else self._api + path_or_url
        if not url.startswith(self._api + "/"):
            raise GitHubError("URL hors de l'API GitHub configurée")
        headers = {
            "Authorization": f"Bearer {bearer}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": _API_VERSION,
            "User-Agent": "pr-sentinel",
        }
        try:
            resp = self._http.request(method, url, headers=headers, **kwargs)
        except httpx.HTTPError as exc:
            raise GitHubError(f"{method} {url.removeprefix(self._api)} : {type(exc).__name__}") from exc
        if resp.status_code >= 400:
            raise GitHubError(
                f"{method} {url.removeprefix(self._api)} : HTTP {resp.status_code}",
                status=resp.status_code,
            )
        return resp
