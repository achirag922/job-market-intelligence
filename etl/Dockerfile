# syntax=docker/dockerfile:1
# Spring Batch ETL: runs one job and exits. Build context: the repository root.

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY database database
COPY etl etl
COPY backend/pom.xml backend/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl etl -am package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system jmip && useradd --system --gid jmip --home-dir /app jmip
COPY --from=build /workspace/etl/target/etl-*.jar /app/app.jar
USER jmip
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
# Job parameters are passed as arguments, e.g. inputFile=/data/raw/synthetic-job-postings-v1.json
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
