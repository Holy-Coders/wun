# syntax=docker/dockerfile:1.7

# ---------------------------------------------------------------------------
# Stage 1: build the uberjar + the cljs bundle.
# ---------------------------------------------------------------------------
FROM clojure:temurin-21-tools-deps-bookworm AS build

# Node is required for shadow-cljs (the web bundle).
RUN apt-get update \
 && apt-get install -y --no-install-recommends nodejs npm \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

# Cache deps before copying source so re-builds skip the dep download
# when only sources change.
COPY deps.edn ./
COPY package.json ./
RUN clojure -P -X:build || true
RUN npm install --no-audit --no-fund --silent

# Copy everything else.
COPY . .

# Build the web bundle into public/js.
RUN npx shadow-cljs release app

# Build the server uberjar (target/myapp.jar).
RUN clojure -T:build uber

# ---------------------------------------------------------------------------
# Stage 2: tiny runtime image.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Copy the uberjar + the static web bundle the server serves.
COPY --from=build /workspace/target/myapp.jar /app/myapp.jar
COPY --from=build /workspace/public            /app/public

# Persistent storage mount-point for the embedded DBs (sqlite, datomic).
# Compose / k8s mount a volume here.
RUN mkdir -p /app/data

ENV PORT=8080
ENV HOST=0.0.0.0
ENV WUN_STATIC=/app/public
ENV LOG_LEVEL=info

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
  CMD wget -qO- http://localhost:${PORT}/healthz || exit 1

ENTRYPOINT ["java", "-jar", "/app/myapp.jar"]
