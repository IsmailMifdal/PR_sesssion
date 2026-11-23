import json

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from pr_sentinel.comment import MARKER
from pr_sentinel.github import GitHubApp, GitHubError

API = "https://api.github.test"


@pytest.fixture(scope="module")
def keypair():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
    ).decode()
    return pem, key.public_key()


class FakeGitHub:
    """Faux serveur GitHub minimal pour httpx.MockTransport."""

    def __init__(self, comments=None, expires_at="2023-11-14T23:13:20Z"):
        self.expires_at = expires_at
        self.comments = comments or []
        self.requests: list[httpx.Request] = []
        self.token_calls = 0
        self.token_requests: list[dict] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        path = request.url.path
        if path.endswith("/access_tokens") and request.method == "POST":
            self.token_calls += 1
            self.token_requests.append(json.loads(request.content))
            return httpx.Response(
                201,
                json={"token": f"ghs_inst_{self.token_calls}", "expires_at": self.expires_at},
            )
        if self.comments is None:
            return httpx.Response(500, json={"message": "boom"})
        if path.endswith("/comments") and request.method == "GET":
            page = int(request.url.params.get("page", "1"))
            chunk = self.comments[(page - 1) * 2 : page * 2]
            headers = {}
            if page * 2 < len(self.comments):
                headers["Link"] = f'<{API}{path}?per_page=100&page={page + 1}>; rel="next"'
            return httpx.Response(200, json=chunk, headers=headers)
        if request.method == "POST" and path.endswith("/comments") and self.comments is not None:
            body = json.loads(request.content)["body"]
            self.comments.append(
                {"id": 500 + len(self.comments), "body": body, "performed_via_github_app": {"id": 42}}
            )
            return httpx.Response(201, json=self.comments[-1])
        if request.method == "PATCH":
            cid = int(path.rsplit("/", 1)[1])
            for c in self.comments:
                if c["id"] == cid:
                    c["body"] = json.loads(request.content)["body"]
                    return httpx.Response(200, json=c)
        return httpx.Response(404, json={"message": "Not Found"})


def make_app(keypair, fake, clock=lambda: 1_700_000_000.0):
    return GitHubApp(
        42, keypair[0], api_url=API, http=httpx.Client(transport=httpx.MockTransport(fake)), clock=clock
    )


def test_app_jwt_claims(keypair):
    app = make_app(keypair, FakeGitHub())
    claims = jwt.decode(
        app.app_jwt(), keypair[1], algorithms=["RS256"], options={"verify_exp": False, "verify_iat": False}
    )
    assert claims["iss"] == "42"
    assert claims["exp"] - claims["iat"] <= 600


def test_installation_token_cached(keypair):
    fake = FakeGitHub()
    now = [1_700_000_000.0]
    app = make_app(keypair, fake, clock=lambda: now[0])
    assert app.installation_token(1, "acme/widgets") == "ghs_inst_1"
    assert app.installation_token(1, "acme/widgets") == "ghs_inst_1"
    assert fake.token_calls == 1
    auth = fake.requests[0].headers["Authorization"]
    assert auth.startswith("Bearer ey")  # JWT de l'app, pas le jeton d'installation
    # Jeton limité au dépôt de la PR et aux permissions minimales.
    assert fake.token_requests[0] == {
        "repositories": ["widgets"],
        "permissions": {"contents": "read", "pull_requests": "write"},
    }
    # expires_at = 1_700_003_600 : renouvelé 5 min avant.
    now[0] = 1_700_003_600 - 299
    assert app.installation_token(1, "acme/widgets") == "ghs_inst_2"


def test_token_cache_is_per_repo_and_invalidatable(keypair):
    fake = FakeGitHub()
    app = make_app(keypair, fake)
    assert app.installation_token(1, "acme/a") == "ghs_inst_1"
    assert app.installation_token(1, "acme/b") == "ghs_inst_2"
    app.invalidate_token(1, "acme/a")
    assert app.installation_token(1, "acme/a") == "ghs_inst_3"


def test_creates_comment_when_absent(keypair):
    fake = FakeGitHub(comments=[{"id": 1, "body": "humain", "performed_via_github_app": None}])
    app = make_app(keypair, fake)
    cid = app.upsert_report_comment("ghs_x", "acme/widgets", 7, MARKER + "\nrapport")
    assert cid == 501
    post = fake.requests[-1]
    assert post.method == "POST" and post.url.path == "/repos/acme/widgets/issues/7/comments"
    assert post.headers["Authorization"] == "Bearer ghs_x"


def test_updates_own_comment_across_pages(keypair):
    fake = FakeGitHub(
        comments=[
            {"id": 1, "body": "a"},
            {"id": 2, "body": "b"},
            {"id": 3, "body": MARKER + " usurpé", "performed_via_github_app": {"id": 7}},
            {"id": 4, "body": MARKER + " v1", "performed_via_github_app": {"id": 42}},
        ]
    )
    app = make_app(keypair, fake)
    assert app.upsert_report_comment("t", "acme/widgets", 7, MARKER + " v2") == 4
    assert fake.requests[-1].method == "PATCH"
    assert fake.comments[3]["body"].endswith("v2")
    assert fake.comments[2]["body"].endswith("usurpé")


def test_http_errors_raise_without_leaking_token(keypair):
    app = make_app(keypair, lambda r: httpx.Response(403, json={"message": "nope"}))
    with pytest.raises(GitHubError) as exc:
        app.upsert_report_comment("ghs_secret", "acme/widgets", 7, "x")
    assert "403" in str(exc.value) and "ghs_secret" not in str(exc.value)
    assert exc.value.status == 403 and exc.value.permanent


@pytest.mark.parametrize(("status", "permanent"), [(401, False), (404, True), (500, False), (None, False)])
def test_permanent_statuses(status, permanent):
    assert GitHubError("x", status=status).permanent is permanent


def test_comment_pagination_is_capped(keypair):
    calls = []

    def endless(request):
        calls.append(request)
        nxt = f"{API}/repos/acme/widgets/issues/7/comments?page={len(calls) + 1}"
        return httpx.Response(200, json=[{"id": 1, "body": "x"}], headers={"Link": f'<{nxt}>; rel="next"'})

    assert make_app(keypair, endless).find_report_comment("t", "acme/widgets", 7) is None
    assert len(calls) == 30


def test_transport_error_wrapped(keypair):
    def boom(request):
        raise httpx.ConnectError("down")

    with pytest.raises(GitHubError, match="ConnectError"):
        make_app(keypair, boom).installation_token(1, "acme/widgets")


def test_invalid_token_response(keypair):
    app = make_app(keypair, lambda r: httpx.Response(201, json={"nope": 1}))
    with pytest.raises(GitHubError):
        app.installation_token(1, "acme/widgets")


def test_refuses_pagination_outside_api(keypair):
    def handler(request):
        return httpx.Response(200, json=[], headers={"Link": '<https://evil.test/x>; rel="next"'})

    with pytest.raises(GitHubError, match="hors de l'API"):
        make_app(keypair, handler).find_report_comment("t", "acme/widgets", 7)


def test_requires_app_id(keypair):
    with pytest.raises(ValueError):
        GitHubApp(0, keypair[0])
