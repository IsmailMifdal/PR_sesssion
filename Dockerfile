# syntax=docker/dockerfile:1
FROM python:3.13-slim-bookworm

ARG GITLEAKS_VERSION=8.30.0
ARG GITLEAKS_SHA256=79a3ab579b53f71efd634f3aaf7e04a0fa0cf206b7ed434638d1547a2470a66e
ARG TRIVY_VERSION=0.76.1
ARG TRIVY_SHA256=c7cbe3137fb8a48a17574bede3dd6beb16742d1e9772929940fc90082c39d9c0
ARG SEMGREP_VERSION=1.186.0

RUN apt-get update \
 && apt-get install -y --no-install-recommends git ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*

# Scanners en versions épinglées. Les SHA-256 sont figés ici (et non relus depuis la release),
# donc une release compromise après coup échoue au build.
RUN set -eux; cd /tmp; \
    gl="gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz"; \
    curl -fsSLO "https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/${gl}"; \
    echo "${GITLEAKS_SHA256}  ${gl}" | sha256sum -c -; \
    tar -xzf "${gl}" gitleaks; install gitleaks /usr/local/bin/; \
    tv="trivy_${TRIVY_VERSION}_Linux-64bit.tar.gz"; \
    curl -fsSLO "https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/${tv}"; \
    echo "${TRIVY_SHA256}  ${tv}" | sha256sum -c -; \
    tar -xzf "${tv}" trivy; install trivy /usr/local/bin/; \
    rm -f gitleaks trivy "${gl}" "${tv}"; \
    apt-get purge -y --auto-remove curl

# Semgrep dans son propre venv pour isoler ses dépendances de celles de l'app.
RUN python -m venv /opt/semgrep \
 && /opt/semgrep/bin/pip install --no-cache-dir "semgrep==${SEMGREP_VERSION}" \
 && ln -s /opt/semgrep/bin/semgrep /usr/local/bin/semgrep

COPY --from=ghcr.io/astral-sh/uv:0.8.22 /uv /usr/local/bin/uv
WORKDIR /app
COPY pyproject.toml uv.lock README.md ./
RUN uv sync --frozen --no-dev --no-install-project
COPY pr_sentinel ./pr_sentinel
RUN uv sync --frozen --no-dev

RUN useradd --create-home --uid 10001 sentinel
USER sentinel
ENV PATH="/app/.venv/bin:${PATH}" \
    TRIVY_CACHE_DIR=/home/sentinel/.cache/trivy \
    PYTHONUNBUFFERED=1

EXPOSE 8000
ENTRYPOINT ["pr-sentinel"]
CMD ["serve"]
