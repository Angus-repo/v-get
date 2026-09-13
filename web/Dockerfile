FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q verify

FROM eclipse-temurin:17-jre-noble
ARG YT_DLP_VERSION=2026.8.19
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv ffmpeg ca-certificates curl \
    && python3 -m venv /opt/ytdlp \
    && /opt/ytdlp/bin/pip install --no-cache-dir "yt-dlp==${YT_DLP_VERSION}" \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app
ENV YT_DLP_BIN=/opt/ytdlp/bin/yt-dlp VIDEO_WORK_DIR=/tmp/clippocket
WORKDIR /app
COPY --from=build /build/target/clippocket-1.0.0.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=35s --retries=3 CMD curl -fsS http://127.0.0.1:8080/api/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=25.0", "-jar", "/app/app.jar"]
