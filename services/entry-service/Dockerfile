FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY libs/common-messaging ./libs/common-messaging
COPY services/entry-service ./services/entry-service

WORKDIR /workspace/services/entry-service
RUN ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/services/entry-service/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
