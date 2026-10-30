FROM eclipse-temurin:21.0.12.1_1-jre-jammy
RUN groupadd --system --gid 10001 supportflow \
    && useradd --system --uid 10001 --gid 10001 --no-create-home --home-dir /app supportflow
WORKDIR /app
COPY --chown=10001:10001 target/supportflow-backend-@@VERSION@@.jar /app/app.jar
COPY --chown=10001:10001 target/rag-native/ai/onnxruntime/native/linux-x64/ /app/native/
USER 10001:10001
EXPOSE 8080 9091
ENTRYPOINT ["java", "-Donnxruntime.native.path=/app/native", "-XX:MaxRAMPercentage=60.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
