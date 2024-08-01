FROM eclipse-temurin:11-jre-alpine

WORKDIR /app

COPY build/libs/LutrineTTS.jar /app

ENTRYPOINT [ "java", "-jar", "LutrineTTS.jar" ]