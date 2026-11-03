# ---- build stage: compile, package and split the Spring Boot jar into layers ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
RUN ./mvnw -B -DskipTests package \
    && java -Djarmode=tools -jar target/reliable-push-backend-*.jar extract --layers --launcher --destination /build/extracted \
    && chmod -R g=u /build/extracted

# ---- runtime stage: JRE only, non-root, OpenShift arbitrary UID (group 0) ----
FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.title="reliable-push-backend" \
      org.opencontainers.image.description="Reliable push delivery/read tracking service with webhook fallback" \
      org.opencontainers.image.source="https://github.com/valentirob/reliable-push" \
      org.opencontainers.image.licenses="MIT"

# Owned by 1001:0 with group permissions equal to the owner's (g=u, set in the build stage): OpenShift runs the container with a random UID in group 0.
# The application writes only under /tmp (world-writable by default), so the root filesystem can be read-only.
WORKDIR /app
COPY --from=build --chown=1001:0 /build/extracted/dependencies/ ./
COPY --from=build --chown=1001:0 /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=1001:0 /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=1001:0 /build/extracted/application/ ./

USER 1001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# Liveness/readiness are Kubernetes probes (/actuator/health/liveness, /readiness), not a Docker health check.
HEALTHCHECK NONE
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
