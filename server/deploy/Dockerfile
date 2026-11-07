# OpenBill Server image (linux/amd64 and linux/arm64). Build from the repository root after
# `./gradlew :server:fatJar` (the Android-capable Gradle build stays outside the image):
#   docker buildx build --platform linux/amd64,linux/arm64 -f server/deploy/Dockerfile -t openbill-server .
FROM eclipse-temurin:21-jre-noble

# curl for the health check only.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 openbill \
    && useradd --system --uid 10001 --gid openbill --home-dir /data --shell /usr/sbin/nologin openbill \
    && mkdir -p /data /app \
    && chown openbill:openbill /data \
    && chmod 0700 /data

COPY server/build/libs/openbill-server-*-all.jar /app/openbill-server.jar

# LAN default: HTTPS with a self-signed certificate kept in /data/keys (fingerprint in the log).
# Behind the Compose Caddy profile these become plain HTTP on the private network (docker-compose.yml).
ENV OPENBILL_DATA_DIR=/data \
    OPENBILL_SERVER_BIND=0.0.0.0 \
    OPENBILL_SERVER_PORT=8090 \
    OPENBILL_TLS_MODE=self_signed \
    JAVA_OPTS="-Xmx256m -XX:+UseSerialGC"

USER openbill
VOLUME ["/data"]
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fsk https://127.0.0.1:8090/healthz || curl -fs http://127.0.0.1:8090/healthz || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/openbill-server.jar serve"]
