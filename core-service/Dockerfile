FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY common-messaging ./common-messaging
COPY core-service ./core-service

WORKDIR /workspace/core-service
RUN ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/core-service/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
