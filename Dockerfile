FROM eclipse-temurin:21-jre-alpine

RUN mkdir -p /data
WORKDIR /app

COPY build/libs/LutrineTTS.jar /app

ENTRYPOINT ["java", "-jar", "LutrineTTS.jar"]