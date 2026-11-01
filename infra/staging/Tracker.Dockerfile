# Optional, NOT deployed automatically. Build from repo root using Java 21.
FROM gradle:8.10.2-jdk21 AS builder
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon :tracker-server:installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 p2p
COPY --from=builder --chown=10001:10001 /workspace/tracker-server/build/install/tracker-server /app
USER 10001:10001
EXPOSE 9000 9001
ENTRYPOINT ["/app/bin/tracker-server"]
