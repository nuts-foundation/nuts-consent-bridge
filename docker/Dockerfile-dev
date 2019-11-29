FROM openjdk:8-alpine as builder
LABEL maintainer="wout.slakhorst@nuts.nl"
COPY / .
RUN ./gradlew bootJar

FROM adoptopenjdk/openjdk11:alpine-slim
COPY --from=builder impl/build/libs/consent-bridge-0.11.0.jar /opt/nuts/consent-bridge-0.11.0.jar
EXPOSE 8080 5563
CMD ["java", "-jar", "/opt/nuts/consent-bridge-0.11.0.jar", "--spring.config.location=file:/opt/nuts/application.properties"]
