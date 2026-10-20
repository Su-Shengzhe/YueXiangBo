# Built artifacts come from the JDK 21 Maven Wrapper build on the host.
# The runtime image is supplied by Build-AppImage.ps1 from images.lock.json.
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
FROM ${JAVA_RUNTIME_IMAGE}
WORKDIR /app
ARG MODULE=parking-service
COPY backend/${MODULE}/target/${MODULE}-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "/app/app.jar"]
