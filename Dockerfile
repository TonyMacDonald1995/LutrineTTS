FROM openjdk:11
ADD https://github.com/TonyMacDonald1995/LutrineTTS/releases/latest/download/LutrineTTS.jar /opt
WORKDIR /opt
ENTRYPOINT [ "java", "-jar", "LutrineTTS.jar" ]