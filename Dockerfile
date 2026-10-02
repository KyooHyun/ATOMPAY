# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 atompay
WORKDIR /app
COPY --from=build /workspace/target/card-pay-core-*.jar app.jar
USER atompay
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=mysql
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
