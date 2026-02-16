
FROM eclipse-temurin:11 AS builder
WORKDIR /app
COPY . .
RUN ./gradlew bootJar

FROM eclipse-temurin:11
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
COPY --from=builder /app/config/elastic/elastic-apm-agent-1.54.0.jar /config/elastic/elastic-apm-agent.jar
CMD ["java", "-jar", "app.jar"]
