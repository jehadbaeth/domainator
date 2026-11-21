# syntax=docker/dockerfile:1

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
# dependencies first so they are cached between builds
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test && cp build/libs/backend-*-SNAPSHOT.jar /app.jar

FROM eclipse-temurin:17-jre
RUN groupadd --system app && useradd --system --gid app --home /app app \
 && mkdir -p /app/cache && chown app:app /app/cache
WORKDIR /app
COPY --from=build /app.jar app.jar
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    DOMAINATOR_CACHE_DIR=/app/cache
# price lists survive restarts here; mount a volume on it
VOLUME /app/cache
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3 && grep -q "UP" <&3'
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
