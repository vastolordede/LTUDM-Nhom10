# Optional, NOT deployed automatically. Build from repo root using Java 21.
FROM gradle:8.10.2-jdk21 AS builder
WORKDIR /workspace
# Explicit allowlist: no local .env, certs, private keys, test data or backups.
COPY settings.gradle build.gradle gradle.properties ./
COPY common/build.gradle ./common/
COPY common/src ./common/src
COPY tracker-server/build.gradle ./tracker-server/
COPY tracker-server/src ./tracker-server/src
COPY peer-client/build.gradle ./peer-client/
RUN gradle --no-daemon :tracker-server:installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 p2p
COPY --from=builder --chown=10001:10001 /workspace/tracker-server/build/install/tracker-server /app
USER 10001:10001
EXPOSE 9000 9001
ENTRYPOINT ["/app/bin/tracker-server"]
