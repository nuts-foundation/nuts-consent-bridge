FROM openjdk:8-alpine as builder
LABEL maintainer="wout.slakhorst@nuts.nl"
COPY / .
RUN ./gradlew bootJar

FROM adoptopenjdk/openjdk11:alpine-slim
COPY --from=builder impl/build/libs/consent-bridge-*.jar /opt/nuts/consent-bridge.jar
EXPOSE 8080 5563
CMD ["java", "-jar", "/opt/nuts/consent-bridge.jar", "--spring.config.location=file:/opt/nuts/application.properties"]
