FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY common-messaging ./common-messaging
COPY entry-service ./entry-service

WORKDIR /workspace/entry-service
RUN ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/entry-service/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
