# syntax=docker/dockerfile:1
# Image unique de PR Sentinel : JDK 21 + Semgrep + Gitleaks + Trivy (base de vulnérabilités intégrée).
# Rôles : API (webhook + tableau de bord) et/ou worker, selon SENTINEL_WORKER_ENABLED.

# --- Build ---------------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /src
COPY backend/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
RUN mvn -q -B -DskipTests package && cp target/pr-sentinel-*.jar /app.jar

# --- Exécution -------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble

ARG GITLEAKS_VERSION=8.30.0
ARG GITLEAKS_SHA256=79a3ab579b53f71efd634f3aaf7e04a0fa0cf206b7ed434638d1547a2470a66e
ARG TRIVY_VERSION=0.76.1
ARG TRIVY_SHA256=c7cbe3137fb8a48a17574bede3dd6beb16742d1e9772929940fc90082c39d9c0
ARG SEMGREP_VERSION=1.186.0

RUN apt-get update \
 && apt-get install -y --no-install-recommends git ca-certificates curl python3 python3-venv \
 && rm -rf /var/lib/apt/lists/*

# Scanners en versions épinglées ; SHA-256 figés ici (une release modifiée après coup échoue au build).
RUN set -eux; cd /tmp; \
    gl="gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz"; \
    curl -fsSLO "https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/${gl}"; \
    echo "${GITLEAKS_SHA256}  ${gl}" | sha256sum -c -; \
    tar -xzf "${gl}" gitleaks; install gitleaks /usr/local/bin/; \
    tv="trivy_${TRIVY_VERSION}_Linux-64bit.tar.gz"; \
    curl -fsSLO "https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/${tv}"; \
    echo "${TRIVY_SHA256}  ${tv}" | sha256sum -c -; \
    tar -xzf "${tv}" trivy; install trivy /usr/local/bin/; \
    rm -f gitleaks trivy "${gl}" "${tv}"

# Semgrep dans son propre venv.
RUN python3 -m venv /opt/semgrep \
 && /opt/semgrep/bin/pip install --no-cache-dir "semgrep==${SEMGREP_VERSION}" \
 && ln -s /opt/semgrep/bin/semgrep /usr/local/bin/semgrep \
 && apt-get purge -y --auto-remove curl

RUN useradd --create-home --uid 10001 sentinel
USER sentinel
ENV TRIVY_CACHE_DIR=/home/sentinel/.cache/trivy \
    SENTINEL_TRIVY_OFFLINE_DB=true \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

# Base de vulnérabilités Trivy intégrée à l'image : aucun téléchargement pendant l'analyse.
# Reconstruire l'image (release hebdomadaire) pour la rafraîchir.
RUN trivy image --download-db-only --no-progress && trivy image --download-java-db-only --no-progress

COPY --from=build /app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
