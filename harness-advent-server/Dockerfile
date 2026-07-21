FROM gradle:9.5.1-jdk21 AS build

WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies
COPY src ./src
RUN ./gradlew --no-daemon installDist

# Gradle проверяет подключённый KMP-проект внутри runtime-контейнера и требует
# javac для компиляции Kotlin DSL catalog accessors.
FROM eclipse-temurin:21-jdk

ARG ANDROID_COMMAND_LINE_TOOLS_VERSION=15859902
ARG ANDROID_COMMAND_LINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
ARG ANDROID_COMPILE_SDK=36
ARG ANDROID_LEGACY_BUILD_TOOLS=35.0.0
ARG ANDROID_BUILD_TOOLS=36.0.0
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_USER_HOME=/tmp/.android
ENV PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}

RUN apt-get update \
    && apt-get install --no-install-recommends --yes curl git unzip \
    && mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && curl --fail --location --silent --show-error \
        --output /tmp/command-line-tools.zip \
        https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_COMMAND_LINE_TOOLS_VERSION}_latest.zip \
    && echo "${ANDROID_COMMAND_LINE_TOOLS_SHA256}  /tmp/command-line-tools.zip" | sha256sum --check \
    && unzip -q /tmp/command-line-tools.zip -d ${ANDROID_HOME}/cmdline-tools \
    && mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
    && yes | sdkmanager --sdk_root=${ANDROID_HOME} --licenses > /dev/null \
    && sdkmanager --sdk_root=${ANDROID_HOME} \
        "platform-tools" \
        "platforms;android-${ANDROID_COMPILE_SDK}" \
        "build-tools;${ANDROID_LEGACY_BUILD_TOOLS}" \
        "build-tools;${ANDROID_BUILD_TOOLS}" \
    && rm -f /tmp/command-line-tools.zip \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 harness \
    && useradd --uid 10001 --gid harness --home-dir /var/lib/harness/home --shell /usr/sbin/nologin harness \
    && mkdir -p /var/lib/harness/home/.android /workspace /config \
    && chown -R harness:harness /var/lib/harness /workspace

WORKDIR /app
COPY --from=build --chown=harness:harness /src/build/install/harness-advent-server ./

USER harness
EXPOSE 8095
ENTRYPOINT ["/app/bin/harness-advent-server"]
