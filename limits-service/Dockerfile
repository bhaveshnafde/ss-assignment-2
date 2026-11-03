# syntax=docker/dockerfile:1
# limits-service image. Built by "docker compose up --build" from the repository root.
#
# Stage 1 builds the jar with Maven; stage 2 runs it on a small Java 21 runtime.
# No password or .env file goes into the image: configuration comes from environment
# variables at run time (see docker-compose.yml and .dockerignore).

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Dependencies first, so they are cached and only re-downloaded when pom.xml changes
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Tests run separately with "mvn verify"; the image build only packages the jar
RUN mvn -B -q -DskipTests package && cp target/*.jar app.jar

# ---------- run ----------
FROM eclipse-temurin:21-jre-alpine
# curl for the health check; a non-root user to run the service
RUN apk add --no-cache curl \
 && addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER app

ENV PORT=8084 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8084

# Docker marks the container healthy only when /health answers 200 (it also checks the database)
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 \
  CMD curl -fs http://localhost:${PORT}/health > /dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
