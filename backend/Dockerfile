# syntax=docker/dockerfile:1
# Spring Boot API. Build context: the repository root, because the backend depends on the
# shared parent POM and the database module (Flyway migrations).

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY database database
COPY backend backend
COPY etl/pom.xml etl/pom.xml
# The cache mount keeps downloaded dependencies between builds without baking them into a layer.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl backend -am package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system jmip && useradd --system --gid jmip --home-dir /app jmip \
    && mkdir -p /app/data/resumes && chown -R jmip:jmip /app
COPY --from=build /workspace/backend/target/backend-*.jar /app/app.jar
USER jmip
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" \
    JMIP_RESUME_DIR=/app/data/resumes
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fs http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
