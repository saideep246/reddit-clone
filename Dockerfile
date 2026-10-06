# Backend image: builds the Spring Boot jar, then runs it on a small JRE image.
# Build stage — dependencies are fetched in their own layer so editing source doesn't re-download them.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package && cp target/reddit-clone-*.jar /workspace/app.jar

# Run stage. Alpine base keeps this image much smaller than the Debian/Ubuntu one — apt's ffmpeg package pulls a far
# larger closure of codec libraries than Alpine's. ffmpeg is needed by media.VideoProcessingWorker (video/GIF
# transcoding); curl is for the health check.
FROM eclipse-temurin:25-jre-alpine
RUN apk add --no-cache ffmpeg curl
# Run as an unprivileged user. Nothing is written outside the JVM/ffmpeg temp directory (/tmp), so no writable volume is needed.
RUN addgroup -S app && adduser -S -D -H -u 10001 -G app app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar app.jar
USER app
# The port comes from the PORT environment variable (Render injects it; application.yml falls back to 8081 locally).
EXPOSE 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "app.jar"]
