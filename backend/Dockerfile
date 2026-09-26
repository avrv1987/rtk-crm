# syntax=docker/dockerfile:1.7
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B package -Dmaven.test.skip=true

FROM build AS test
RUN --mount=type=cache,target=/root/.m2 mvn -B test

FROM eclipse-temurin:21.0.8_9-jre
RUN useradd --system --create-home crm && mkdir --parents /var/lib/rtk-crm/files && chown --recursive crm:crm /var/lib/rtk-crm
WORKDIR /app
COPY --from=build /workspace/target/rtk-crm-backend-0.1.0-SNAPSHOT.jar app.jar
USER crm
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
