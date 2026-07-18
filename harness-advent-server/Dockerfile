FROM gradle:9.5.1-jdk21 AS build

WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies
COPY src ./src
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre

RUN groupadd --gid 10001 harness \
    && useradd --uid 10001 --gid harness --home-dir /nonexistent --shell /usr/sbin/nologin harness \
    && mkdir -p /var/lib/harness /workspace /config \
    && chown harness:harness /var/lib/harness /workspace

WORKDIR /app
COPY --from=build --chown=harness:harness /src/build/install/harness-advent-server ./

USER harness
EXPOSE 8095
ENTRYPOINT ["/app/bin/harness-advent-server"]
