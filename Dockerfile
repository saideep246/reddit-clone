# Backend image: builds the Spring Boot jar, then runs it on a small JRE image.
# Build stage — dependencies are fetched in their own layer so editing source doesn't re-download them.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package && cp target/reddit-clone-*.jar /workspace/app.jar

# Run stage. ffmpeg is needed by media.VideoProcessingWorker (video/GIF transcoding); curl is for the health check.
FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
EXPOSE 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"
ENTRYPOINT ["java", "-jar", "app.jar"]
