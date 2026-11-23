# Stage 1: build (Maven and the full JDK, not shipped)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
# Copy the pom first so the dependency layer is cached until the dependencies change.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Tests run in CI as a separate stage, so the image build only packages.
RUN mvn -B -q package -DskipTests && cp target/payment-event-relay-*.jar target/app.jar

# Stage 2: runtime (JRE only, keeps the image small)
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app \
    && mkdir -p /app/data && chown -R app:app /app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/app.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
# Size the heap from the container's memory limit, not the host's RAM.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
