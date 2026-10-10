# Empty by default. build-images.yml overrides this stage with the named build context "gitdir"
# (the checkout's .git), so the jar's git.properties carries the built commit (ADR 0022).
# BuildKit replaces a stage of the same name with a named context.
FROM scratch AS gitdir

# Stage 1: build the jar with Gradle
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Copy build files first so dependency download is cached
# until build.gradle actually changes
COPY gradlew build.gradle settings.gradle gradle.lockfile ./
RUN chmod +x gradlew
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon > /dev/null 2>&1 || true

COPY src ./src
# Tests already ran in CI; here we only build the artifact.
# The bind mount is read-only and never copied into a layer: Gradle reads .git through
# -PgitDir (see build.gradle). With REQUIRE_GIT_DIR=true a missing context, or a plugin that
# silently generated nothing, fails the image build instead of surfacing later as an e2e timeout.
ARG REQUIRE_GIT_DIR=false
RUN --mount=type=bind,from=gitdir,target=/tmp/gitdir \
    if [ -f /tmp/gitdir/HEAD ]; then set -- -PgitDir=/tmp/gitdir; \
    elif [ "$REQUIRE_GIT_DIR" = true ]; then echo "build context gitdir (.git) is missing" >&2; exit 1; fi; \
    ./gradlew bootJar --no-daemon "$@" \
    && if [ "$REQUIRE_GIT_DIR" = true ] && [ ! -f build/resources/main/git.properties ]; then \
         echo "git.properties was not generated" >&2; exit 1; fi

# Stage 2: minimal runtime image (JRE only, no Gradle, no sources). build-images.yml excludes
# the stage "runtime" from the layer cache (no-cache-filters), so the upgrade runs on every CI build.
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# Pick up Ubuntu security fixes published since the base image was built (as root, before USER).
# /usr/bin/pebble comes from the Ubuntu 26.04 base image and the application does not use it
# (the entrypoint is java). It belongs to no dpkg package, so apt never updates it: remove it
# so that dead code is not scanned.
RUN apt-get update && apt-get upgrade -y && rm -f /usr/bin/pebble && rm -rf /var/lib/apt/lists/*

# Run as a non-root user
RUN useradd --system --uid 1001 app
USER 1001

COPY --from=build /app/build/libs/*.jar app.jar

# Runtime env vars (no defaults with credentials are baked into the image):
#   DB_URL, DB_USER, DB_PASSWORD, DB_STARTUP_WAIT_TIMEOUT (default 60s),
#   DB_STARTUP_WAIT_INTERVAL (default 2s)
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
