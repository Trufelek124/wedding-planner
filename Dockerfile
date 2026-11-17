# syntax=docker/dockerfile:1

# The planner as one image: the backend with the built web app inside it.
#
#   docker build -t wedding-planner .          (from the repository root)
#
# What the container needs when it runs is in docs/deployment.md.
# The two build stages run on the machine that builds, whatever machine the image is for:
# npm and Gradle work natively there, and only the last stage is the target's.

# ---- the web app -------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM node:22-bookworm-slim AS web
WORKDIR /src/web
# Dependencies first: this layer is rebuilt only when they change.
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

# ---- the backend -------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS backend
WORKDIR /src/backend
# The build tool and the libraries first, for the same reason.
COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts ./
COPY backend/gradle ./gradle
RUN ./gradlew --no-daemon --quiet dependencies --configuration runtimeClasspath > /dev/null
COPY backend/ ./
RUN ./gradlew --no-daemon --quiet bootJar \
 && cp build/libs/*.jar /app.jar \
 # Taken apart into layers: libraries change seldom, our own code with every commit,
 # so an update of the running server downloads little.
 && java -Djarmode=tools -jar /app.jar extract --layers --destination /layers \
 # The health check is a small program of its own: the runtime image has no curl to ask with.
 && javac -d /health container/Health.java

# ---- what runs ---------------------------------------------------------------------
FROM eclipse-temurin:25-jre

# The commit the image was built from; the publishing step passes it.
ARG REVISION=unknown
LABEL org.opencontainers.image.title="Wedding planner" \
      org.opencontainers.image.revision="${REVISION}"

# Never root. The numbers are fixed so that a volume keeps its owner from one image to the next.
RUN groupadd --gid 10001 app \
 && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin app \
 && mkdir -p /data/media \
 && chown 10001:10001 /data/media

WORKDIR /app
COPY --from=backend /layers/dependencies/ ./
COPY --from=backend /layers/spring-boot-loader/ ./
COPY --from=backend /layers/snapshot-dependencies/ ./
COPY --from=backend /layers/application/ ./
COPY --from=backend /health/ /app/health/
COPY --from=web /src/web/dist/ /app/web/

# Where the web app's files are, and where files that must outlive the container go.
# The process writes nowhere else, except to /tmp: the root file system can be read-only.
ENV APP_WEB_DIR=/app/web \
    APP_MEDIA_DIR=/data/media \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
VOLUME /data/media

USER 10001:10001
EXPOSE 8080

# Healthy means ready: the application answers 200 only while it can reach the database.
HEALTHCHECK --interval=30s --timeout=8s --start-period=60s --start-interval=3s --retries=3 \
  CMD ["java", "-XX:TieredStopAtLevel=1", "-Xmx32m", "-cp", "/app/health", "Health"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
