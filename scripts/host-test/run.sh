#!/usr/bin/env bash
# Runs the Android-free unit tests (ai, workspace and selected app guards) on a plain JVM,
# without Gradle/AGP, plus a scenario driver for AICoreProvider against a fake ML Kit.
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
  org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar \
  com/squareup/okhttp3/okhttp-jvm/5.5.0/okhttp-jvm-5.5.0.jar \
  com/squareup/okio/okio-jvm/3.18.1/okio-jvm-3.18.1.jar; do
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
  "$A"/provider/providers/AICorePrompt.kt "$A"/provider/providers/AICoreProvider.kt
  "$ROOT"/scripts/host-test/stubs/*.kt   # fake android.* / ML Kit surface
  "$ROOT"/scripts/host-test/AICoreProviderScenario.kt
)
W="$ROOT/workspace/src/main/java/me/rerere/workspace"
SRC+=( "$W"/WorkspaceFileSystem.kt "$W"/SafeFiles.kt "$W"/Workspace.kt )
APP="$ROOT/app/src/main/java/me/rerere/rikkahub"
APPT="$ROOT/app/src/test/java/me/rerere/rikkahub"
SRC+=( "$APP"/data/ai/tools/local/PathSafetyGuard.kt "$APP"/data/ai/net/GuardedDns.kt "$APP"/data/ai/tools/HardlineCommandGuard.kt "$APP"/data/ai/tools/ToolApprovalDefaults.kt )
TESTS=(
  "$T"/provider/providers/AICorePromptTest.kt
  "$ROOT"/workspace/src/test/java/me/rerere/workspace/WorkspaceSymlinkEscapeTest.kt
  "$APPT"/data/ai/tools/local/PathSafetyGuardTest.kt
  "$APPT"/data/ai/tools/local/PathSafetyGuardDevicePathsTest.kt
  "$APPT"/data/ai/net/GuardedDnsTest.kt
  "$APPT"/data/ai/net/BrowserTargetGuardTest.kt
  "$APPT"/data/ai/tools/HardlineCommandGuardTest.kt
  "$APPT"/data/ai/tools/ToolApprovalDefaultsTest.kt
)

"$K/bin/kotlinc" -nowarn -Xplugin="$K/lib/kotlinx-serialization-compiler-plugin.jar" \
  -cp "$CP" -d "$OUT" "${SRC[@]}" "${TESTS[@]}" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
[ -n "$(find "$OUT" -name 'AICorePromptTest*.class' -print -quit)" ] || { echo "compile failed"; exit 1; }
RUN=(java -cp "$OUT:$CP:$K/lib/kotlin-stdlib.jar")
"${RUN[@]}" org.junit.runner.JUnitCore me.rerere.ai.provider.providers.AICorePromptTest \
  me.rerere.workspace.WorkspaceSymlinkEscapeTest \
  me.rerere.rikkahub.data.ai.tools.local.PathSafetyGuardTest \
  me.rerere.rikkahub.data.ai.tools.local.PathSafetyGuardDevicePathsTest \
  me.rerere.rikkahub.data.ai.net.GuardedDnsTest \
  me.rerere.rikkahub.data.ai.net.BrowserTargetGuardTest \
  me.rerere.rikkahub.data.ai.tools.HardlineCommandGuardTest \
  me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaultsTest 2>&1 \
  | grep -v '^Picked up JAVA_TOOL_OPTIONS' | tee "$OUT/junit.log"
grep -q '^OK (' "$OUT/junit.log"
# Drives the real AICoreProvider.streamText against a scripted fake GenerativeModel.
"${RUN[@]}" AICoreProviderScenarioKt 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' | tee "$OUT/scenario.log"
grep -q '^ALL PASS' "$OUT/scenario.log"
