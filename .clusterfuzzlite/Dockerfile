# ClusterFuzzLite build environment for continuous fuzzing of this library.
#
# Two things this image has to add to the OSS-Fuzz JVM base, both forced by the project
# rather than by preference:
#
#  * JDK 21. build.gradle.kts declares `jvmToolchain(21)` and the base image ships 17.
#    JAVA_HOME is deliberately left pointing at 17 — Gradle itself runs fine there and the
#    base image's own tooling expects it. Gradle auto-detects JVMs under /usr/lib/jvm, so
#    installing 21 is enough for the toolchain to resolve.
#
#  * The Android SDK. The Android KMP plugin is applied at the ROOT of this build, and
#    Gradle configures every plugin before it runs any task — so even `:jvmJar`, which
#    produces no Android output at all, cannot CONFIGURE without an SDK present.
FROM gcr.io/oss-fuzz-base/base-builder-jvm

RUN apt-get update && \
    apt-get install -y --no-install-recommends openjdk-21-jdk && \
    rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=$ANDROID_HOME

# Pinned to a specific command-line-tools build rather than a floating "latest" alias:
# a fuzzing image that silently changes toolchain under you is the same class of problem
# as an unpinned Action.
RUN mkdir -p "$ANDROID_HOME/cmdline-tools" && \
    curl -sSL -o /tmp/cmdline-tools.zip \
      https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip && \
    unzip -q /tmp/cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools" && \
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" && \
    rm /tmp/cmdline-tools.zip && \
    yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null && \
    "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
      "platform-tools" "platforms;android-36" "build-tools;36.0.0" > /dev/null

COPY . $SRC/aisdk-kotlin
COPY .clusterfuzzlite/build.sh $SRC/
WORKDIR $SRC/aisdk-kotlin
