# Gatling runner of scripts/load.sh (LOCAL ONLY, never deployed): JDK + the root Maven wrapper's toolchain.
# Build context = loadtest/ (nothing is copied). The repository is bind-mounted at /work by loadtest/compose/load.override.yml
# and the container runs as the invoking host user; ~/.m2 is a named volume so dependencies download once.
FROM eclipse-temurin:24.0.2_12-jdk

# world-writable (sticky) so a named volume created from this directory is usable by any UID
RUN mkdir -p /home/load/.m2 /work && chmod 1777 /home/load /home/load/.m2
ENV HOME=/home/load
WORKDIR /work
HEALTHCHECK NONE
CMD ["sleep", "infinity"]
