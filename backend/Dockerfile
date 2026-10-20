# =============================================================================
#  Multi-stage build.
#
#  The build stage carries a JDK, Maven, the full dependency cache and the source
#  tree — several hundred megabytes of things an attacker could use if they got a
#  shell. None of it reaches the image that actually runs.
# =============================================================================

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first, so a source-only change reuses the cached layer.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests \
    && mv target/*.jar app.jar

# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

# curl, solely so the HEALTHCHECK below can run.
#
# The JRE image is Ubuntu minimal and ships neither curl nor wget, so the check was
# invoking a binary that does not exist. It failed every interval, and the container
# reported `unhealthy` from the moment it started serving traffic. Nothing gates on
# that today (compose waits on the database, Render probes over HTTP itself), which
# makes it worse rather than better: a health signal that is always red is one nobody
# reads, and it is indistinguishable from a genuinely wedged app.
#
# Installing a network tool into a runtime image is a real cost against the minimalism
# the rest of this stage is built around. It is accepted because the alternative was a
# check that could not work, and dropping the check would leave nothing able to tell a
# booting app from a hung one.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# A non-root user with no shell and no home. If something does manage to execute
# in this container, it starts with as little as possible.
RUN groupadd --system --gid 10001 gfs \
    && useradd --system --uid 10001 --gid gfs --no-create-home --shell /usr/sbin/nologin gfs

WORKDIR /app
COPY --from=build --chown=gfs:gfs /build/app.jar ./app.jar

USER gfs
EXPOSE 8080

# Memory, for a 512 MB instance. The container limit counts everything the JVM uses, not
# just the heap: at 75% of RAM for the heap, metaspace + code cache + thread stacks + direct
# buffers went over 512 MB and Render restarted the service (2026-10-20). So:
#   heap 50% of RAM (256 MB at 512 MB)    MaxRAMPercentage=50
#   class metadata capped                 MaxMetaspaceSize=180m
#   JIT code capped                       ReservedCodeCacheSize=64m
#   NIO direct buffers capped             MaxDirectMemorySize=48m
#   platform thread stacks halved         Xss512k (request threads are virtual)
#   one GC thread, smallest footprint     UseSerialGC
#   an OutOfMemoryError restarts the container instead of leaving it half-alive
#                                         ExitOnOutOfMemoryError
# plus a container-friendly entropy source, so SecureRandom does not block on a fresh VM.
# Render's JAVA_OPTS, when set, replaces all of this; the startup log line
# "JVM MEMORY:" says what the running process actually got.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50 -XX:MaxMetaspaceSize=180m -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=48m -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=30s --timeout=3s --start-period=45s --retries=3 \
    CMD ["sh", "-c", "curl -fsS http://localhost:8080/actuator/health/readiness || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
