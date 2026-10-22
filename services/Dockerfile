# Runtime image for one PharmaVigil Java service. Build the jars first (mvn -B package -DskipTests), then:
#   docker build -f services/Dockerfile --build-arg SERVICE=case-service -t pharmavigil/case-service services
# Runs as a non-root user; configuration comes only from environment variables. The OpenTelemetry Java agent
# (copied by the Maven build) is attached but stays off until OTEL_JAVAAGENT_ENABLED=true.
FROM eclipse-temurin:21-jre-noble
ARG SERVICE
RUN groupadd --system --gid 10001 pv && useradd --system --uid 10001 --gid pv --no-create-home pv
WORKDIR /app
COPY ${SERVICE}/target/otel/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
COPY ${SERVICE}/target/${SERVICE}-*-exec.jar /app/app.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -javaagent:/app/opentelemetry-javaagent.jar" \
    OTEL_JAVAAGENT_ENABLED=false
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
