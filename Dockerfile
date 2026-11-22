# One image for both roles (API and video worker); the role is chosen with VIDEO_WORKER_ENABLED.
# Build the jar first:  ./gradlew bootJar
# Then:                 docker build -t instagram:0.0.1-SNAPSHOT .
FROM eclipse-temurin:26-jre-noble

# The transcoding worker shells out to ffmpeg/ffprobe (FfmpegTranscoder).
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY build/libs/instagram-0.0.1-SNAPSHOT.jar app.jar

# Originals and renditions are written here while a job runs; mount an emptyDir on Kubernetes.
ENV TRANSCODING_WORK_DIR=/work
USER 10001
EXPOSE 8081

# Size the heap from the container's memory limit, not the node's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
