# ipda live demo loop — container image.
#
# Multi-stage: build the Gradle distribution, then ship only the app + JRE 21.
# The loop runs against /data (mounted volume) which must contain:
#   config/ipda-config.json   — the control config (or a variant via args)
#   secrets.properties        — credentials (NEVER in the image; mounted rw so
#                               in-protocol token refresh can persist)
# and receives runs/<live-...>/ artifacts there.
#
# Build (from ipda/):   docker build -t ipda-live .
# Run:                  docker compose up -d   (see ../deploy/docker-compose.yml)

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre
COPY --from=build /src/build/install/ipda /app
# All state lives on the volume; the image is stateless and disposable.
WORKDIR /data
ENTRYPOINT ["/app/bin/ipda-live"]
# Default: config/ipda-config.json relative to /data. Override per container,
# e.g. a second instrument class:  command: ["--config","config/v3-xauusd.json"]
