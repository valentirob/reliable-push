# Local test/demo harness image. Build context = repo root: docker build -f harness/Dockerfile .
# ---- build stage: builds client-java and the harness in one reactor, then splits the jar into layers ----
FROM eclipse-temurin:21.0.13_9-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY client-java/ client-java/
COPY harness/ harness/
RUN ./mvnw -B -q -pl harness -am -Dmaven.test.skip=true package \
    && java -Djarmode=tools -jar harness/target/reliable-push-harness-*.jar extract --layers --launcher --destination /build/extracted \
    && chmod -R g=u /build/extracted

# ---- runtime stage: non-root, OpenShift-style arbitrary UID (group 0); full JRE is fine for a local tool ----
FROM eclipse-temurin:21.0.13_9-jre-alpine-3.24

LABEL org.opencontainers.image.title="reliable-push-harness" \
      org.opencontainers.image.description="Local test/demo harness for reliable-push (never deployed)" \
      org.opencontainers.image.source="https://github.com/valentirob/reliable-push" \
      org.opencontainers.image.licenses="MIT"

WORKDIR /app
COPY --from=build --chown=1001:0 /build/extracted/dependencies/ ./
COPY --from=build --chown=1001:0 /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=1001:0 /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=1001:0 /build/extracted/application/ ./

USER 1001
EXPOSE 8090
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK NONE
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
