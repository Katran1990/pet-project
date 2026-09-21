# Stage 1: build the jar with Gradle
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Copy build files first so dependency download is cached
# until build.gradle actually changes
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon > /dev/null 2>&1 || true

COPY src ./src
# Tests already ran in CI; here we only build the artifact
RUN ./gradlew bootJar --no-daemon

# Stage 2: minimal runtime image (JRE only, no Gradle, no sources)
FROM eclipse-temurin:25-jre
WORKDIR /app

# Run as a non-root user
RUN useradd --system --uid 1001 app
USER 1001

COPY --from=build /app/build/libs/*.jar app.jar

# Runtime env vars (no defaults with credentials are baked into the image):
#   DB_URL, DB_USER, DB_PASSWORD, DB_STARTUP_WAIT_TIMEOUT (default 60s),
#   DB_STARTUP_WAIT_INTERVAL (default 2s)
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
