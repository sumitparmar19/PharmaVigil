# Runtime image for one PharmaVigil Java service. Build the jars first (mvn -B package -DskipTests), then:
#   docker build -f services/Dockerfile --build-arg SERVICE=case-service -t pharmavigil/case-service services
# Runs as a non-root user; configuration comes only from environment variables.
FROM eclipse-temurin:21-jre-noble
ARG SERVICE
RUN groupadd --system --gid 10001 pv && useradd --system --uid 10001 --gid pv --no-create-home pv
WORKDIR /app
COPY ${SERVICE}/target/${SERVICE}-*-exec.jar /app/app.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
