FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM quay.io/keycloak/keycloak:26.8.0
COPY --from=build /workspace/target/keycloak-access-requests.jar /opt/keycloak/providers/keycloak-access-requests.jar
