# ---- build stage: compile, package and split the Spring Boot jar into layers ----
FROM eclipse-temurin:21.0.13_9-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
RUN ./mvnw -B -DskipTests package \
    && java -Djarmode=tools -jar target/reliable-push-backend-*.jar extract --layers --launcher --destination /build/extracted \
    && chmod -R g=u /build/extracted

# ---- jre stage: a trimmed runtime with only the modules the application needs (musl, to match the alpine runtime) ----
FROM eclipse-temurin:21.0.13_9-jdk-alpine-3.24 AS jre
COPY --from=build /build/extracted /extracted
# jdeps finds the modules the code references; the extras are loaded reflectively or by service lookup and are
# invisible to it: TLS/EC crypto (jdk.crypto.ec), Kerberos for the PostgreSQL driver (java.security.jgss),
# JMX/Micrometer (java.management, jdk.management), sun.misc.Unsafe users (jdk.unsupported), Instrumentation
# and JNDI/SQL used by libraries (java.instrument, java.naming, java.sql).
RUN set -eux; \
    CP="$(find /extracted -name '*.jar' | tr '\n' ':')"; \
    MODULES="$(jdeps --ignore-missing-deps --multi-release 21 --print-module-deps \
        --class-path "$CP" /extracted/application/BOOT-INF/classes /extracted/spring-boot-loader)"; \
    jlink --add-modules "$MODULES,jdk.crypto.ec,java.security.jgss,jdk.unsupported,java.instrument,jdk.management,java.management,java.naming,java.sql" \
        --strip-debug --no-man-pages --no-header-files --compress=zip-6 --output /jre; \
    # the JDK's cacerts is a symlink into the alpine trust store: ship the real file so HTTPS webhooks verify
    cp -L "$JAVA_HOME/lib/security/cacerts" /jre/lib/security/cacerts; \
    /jre/bin/java --list-modules

# ---- runtime stage: non-root, OpenShift arbitrary UID (group 0) ----
FROM alpine:3.24.3

LABEL org.opencontainers.image.title="reliable-push-backend" \
      org.opencontainers.image.description="Reliable push delivery/read tracking service with webhook fallback" \
      org.opencontainers.image.source="https://github.com/valentirob/reliable-push" \
      org.opencontainers.image.licenses="MIT"

COPY --from=jre /jre /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:$PATH

# Owned by 1001:0 with group permissions equal to the owner's (g=u, set in the build stage): OpenShift runs the container
# with a random UID in group 0. The application writes only under /tmp (world-writable by default), so the root
# filesystem can be read-only.
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
