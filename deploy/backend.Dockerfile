FROM eclipse-temurin:21.0.12.1_1-jre-jammy

RUN groupadd --system --gid 10001 supportflow \
    && useradd --system --uid 10001 --gid 10001 --no-create-home --home-dir /app supportflow

WORKDIR /app
# Build and verify this final Spring Boot jar before building the runtime image.
# No Maven, source checkout or workstation configuration is copied into the image.
COPY --chown=10001:10001 target/supportflow-backend-0.1.0-SNAPSHOT.jar /app/app.jar

USER 10001:10001
EXPOSE 8080 9091
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
