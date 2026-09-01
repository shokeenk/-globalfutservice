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

# Container-aware heap sizing, and a container-friendly entropy source so
# SecureRandom does not block on a fresh VM with an empty entropy pool.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=30s --timeout=3s --start-period=45s --retries=3 \
    CMD ["sh", "-c", "curl -fsS http://localhost:8080/actuator/health/readiness || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
