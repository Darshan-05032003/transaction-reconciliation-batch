# syntax=docker/dockerfile:1

# Build stage: the Maven wrapper resolves dependencies in a layer of its own, so a change
# under src/ does not download them again; the BuildKit cache keeps ~/.m2 between builds.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests package \
    && java -Djarmode=tools -jar target/transaction-reconciliation-batch-*.jar \
       extract --layers --launcher --destination target/extracted

# Runtime stage: a JRE only, the jar split into layers (dependencies change rarely, the
# application layer on every build), a non-root user and one volume for every data folder
# so that the atomic moves between inbox, archive and failed stay on one file system.
FROM eclipse-temurin:21-jre
RUN useradd --uid 10001 --user-group --home-dir /app --no-create-home --shell /usr/sbin/nologin recon
WORKDIR /app
COPY --from=build --chown=recon:recon /workspace/target/extracted/dependencies/ ./
COPY --from=build --chown=recon:recon /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=recon:recon /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=recon:recon /workspace/target/extracted/application/ ./
RUN mkdir -p data/inbox data/archive data/failed data/error data/reports && chown -R recon:recon data
USER recon

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError" \
    SERVER_PORT=8083 \
    RECON_INBOX_DIR=/app/data/inbox \
    RECON_ARCHIVE_DIR=/app/data/archive \
    RECON_FAILED_DIR=/app/data/failed \
    RECON_ERROR_DIR=/app/data/error \
    RECON_REPORTS_DIR=/app/data/reports
EXPOSE 8083
VOLUME /app/data
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS "http://localhost:${SERVER_PORT}/actuator/health" || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
