#!/bin/bash -eu
# ClusterFuzzLite build step: produce Jazzer fuzz targets in $OUT.
#
# Runs inside the image defined by .clusterfuzzlite/Dockerfile, which supplies JDK 21 for
# the Gradle toolchain and the Android SDK the root build needs in order to configure.

# The configuration cache is on in gradle.properties. It serialises the whole configured
# model, and an init script that registers a task is exactly the input it is not prepared
# for here, so it is disabled for these two invocations only.
GRADLE_FLAGS=(--no-daemon --no-configuration-cache --init-script .clusterfuzzlite/fuzz-classpath.init.gradle)

./gradlew "${GRADLE_FLAGS[@]}" jvmJar fuzzRuntimeLibs

# One jar, matched by shape rather than by name: VERSION_NAME moves every release and a
# hardcoded filename would fail silently at the next bump, leaving a fuzzer built against
# a stale classpath. Sources/javadoc jars are excluded so the glob cannot match two.
mapfile -t LIB_JARS < <(find build/libs -name '*-jvm-*.jar' \
  ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort)
if [ "${#LIB_JARS[@]}" -ne 1 ]; then
  echo "FATAL: expected exactly one jvm jar in build/libs, found ${#LIB_JARS[@]}:" >&2
  printf '  %s\n' "${LIB_JARS[@]}" >&2
  exit 1
fi
LIB_JAR="${LIB_JARS[0]}"

if [ ! -d build/fuzz-libs ] || [ -z "$(ls -A build/fuzz-libs)" ]; then
  echo "FATAL: build/fuzz-libs is empty — fuzzRuntimeLibs resolved no runtime classpath." >&2
  echo "A harness compiled without kotlin-stdlib would fail at runtime, not at build." >&2
  exit 1
fi

# $JAZZER_API_PATH is exported by the base image and holds jazzer-api.jar.
BUILD_CP="$LIB_JAR:$(find "$PWD/build/fuzz-libs" -name '*.jar' | tr '\n' ':')$JAZZER_API_PATH"

mkdir -p "$WORK/fuzz-classes"
# `--release 17`, not bare javac. The Dockerfile installs JDK 21 for Gradle's toolchain,
# which also puts a JDK 21 javac on PATH — but Jazzer's driver runs on the image's JDK 17.
# Without this the harness compiles to class file 65 and the fuzzer dies at startup with
# UnsupportedClassVersionError, AFTER a clean build and a green compile step. 17 is the
# library's own Java target (`options.release.set(17)` in build.gradle.kts), so the
# harness and the code under test agree by construction.
javac --release 17 -cp "$BUILD_CP" -d "$WORK/fuzz-classes" .clusterfuzzlite/FixJsonFuzzer.java

# Guard the above rather than trusting it: a version skew here fails at fuzz time, not at
# build time, so it is exactly the kind of breakage a "BUILD SUCCESSFUL" hides. Compare
# the harness against the library it links, so raising the project's target moves both or
# fails loudly.
class_major() { od -An -tu1 -N8 | awk '{print $8}'; }
harness_major=$(class_major < "$WORK/fuzz-classes/FixJsonFuzzer.class")
lib_major=$(unzip -p "$LIB_JAR" ai/torad/aisdk/PartialJson.class | class_major)
if [ "$harness_major" != "$lib_major" ]; then
  echo "FATAL: harness class file version ($harness_major) != library ($lib_major)." >&2
  echo "The fuzz target would fail to load at runtime. Align javac --release with the" >&2
  echo "project's jvm target in build.gradle.kts." >&2
  exit 1
fi

# Everything the target needs at runtime goes to $OUT; the wrapper's classpath is
# resolved relative to $this_dir so it stays valid wherever ClusterFuzzLite unpacks it.
mkdir -p "$OUT/lib"
cp "$LIB_JAR" "$OUT/lib/"
cp build/fuzz-libs/*.jar "$OUT/lib/"
(cd "$WORK/fuzz-classes" && jar cf "$OUT/lib/fuzz-harness.jar" .)

for target in FixJsonFuzzer; do
  cat > "$OUT/$target" <<EOF
#!/bin/bash
# LLVMFuzzerTestOneInput — ClusterFuzzLite entry point for $target
this_dir=\$(dirname "\$0")
LD_LIBRARY_PATH="\$JVM_LD_LIBRARY_PATH":\$this_dir \\
\$this_dir/jazzer_driver --agent_path=\$this_dir/jazzer_agent_deploy.jar \\
--cp=\$(find "\$this_dir/lib" -name '*.jar' | tr '\n' ':') \\
--target_class=$target \\
--jvm_args="-Xmx2048m" \\
\$@
EOF
  chmod +x "$OUT/$target"
done
