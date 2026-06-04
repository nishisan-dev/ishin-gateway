# syntax=docker/dockerfile:1.7
# ─────────────────────────────────────────────────────
# Ishin Gateway — Multi-stage Docker Image
# ─────────────────────────────────────────────────────

# Stage 1: Build
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /build
COPY pom.xml .
RUN --mount=type=secret,id=maven_settings,target=/tmp/settings.xml,required=true \
    mvn -s /tmp/settings.xml dependency:go-offline -U -q || true
COPY src/ src/
COPY rules/ rules/
RUN --mount=type=secret,id=maven_settings,target=/tmp/settings.xml,required=true \
    mvn -s /tmp/settings.xml -DskipTests clean package -U

# Stage 2: Runtime
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=builder /build/target/ishin-gateway-*.jar app.jar
COPY --from=builder /build/rules/ rules/
EXPOSE 9091 9190 9200 7100 18080
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-Xms128m", "-Xmx256m", "-jar", "app.jar"]
