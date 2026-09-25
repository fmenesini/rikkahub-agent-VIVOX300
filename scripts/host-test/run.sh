#!/usr/bin/env bash
# Runs the Android-free unit tests of the :ai module on a plain JVM, without Gradle/AGP.
# Use it where Google Maven is unreachable (e.g. sandboxed CI/agent containers). On a normal
# dev machine prefer: ./gradlew :ai:testDebugUnitTest
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CACHE="${HOST_TEST_CACHE:-$HOME/.cache/rikkahub-host-test}"
KOTLIN_VERSION="$(sed -n 's/^kotlin = "\(.*\)"/\1/p' "$ROOT/gradle/libs.versions.toml")"
MVN=https://repo.maven.apache.org/maven2
mkdir -p "$CACHE/libs"

if [ ! -x "$CACHE/kotlinc/bin/kotlinc" ]; then
  curl -sSfL -o "$CACHE/kc.zip" \
    "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip"
  (cd "$CACHE" && unzip -q -o kc.zip && rm kc.zip)
fi
for a in \
  org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.11.0/kotlinx-serialization-json-jvm-1.11.0.jar \
  org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.11.0/kotlinx-serialization-core-jvm-1.11.0.jar \
  org/jetbrains/kotlinx/kotlinx-datetime-jvm/0.8.0/kotlinx-datetime-jvm-0.8.0.jar \
  org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.11.0/kotlinx-coroutines-core-jvm-1.11.0.jar \
  junit/junit/4.13.2/junit-4.13.2.jar \
  org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar; do
  f="$CACHE/libs/$(basename "$a")"
  [ -f "$f" ] || curl -sSfL --retry 4 --retry-delay 2 -o "$f" "$MVN/$a"
done

K="$CACHE/kotlinc"
CP="$(ls "$CACHE"/libs/*.jar | tr '\n' ':')"
A="$ROOT/ai/src/main/java/me/rerere/ai"
T="$ROOT/ai/src/test/java/me/rerere/ai"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# Pure sources only: everything listed here must stay free of android.* / ML Kit imports.
SRC=(
  "$A"/ui/*.kt "$A"/core/*.kt "$A"/provider/Model.kt "$A"/provider/Provider.kt "$A"/util/Json.kt
  "$A"/provider/providers/AICorePrompt.kt
  "$ROOT"/scripts/host-test/stubs/*.kt
)
TESTS=( "$T"/provider/providers/AICorePromptTest.kt )

"$K/bin/kotlinc" -nowarn -Xplugin="$K/lib/kotlinx-serialization-compiler-plugin.jar" \
  -cp "$CP" -d "$OUT" "${SRC[@]}" "${TESTS[@]}" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
[ -n "$(find "$OUT" -name 'AICorePromptTest*.class' -print -quit)" ] || { echo "compile failed"; exit 1; }
java -cp "$OUT:$CP:$K/lib/kotlin-stdlib.jar" org.junit.runner.JUnitCore \
  me.rerere.ai.provider.providers.AICorePromptTest 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS'
