# Android build — Dell / Kubuntu

Debug APK for the Vivo X300. Values read from the build files on 2026-09-25. On the Dell,
`:app:testDebugUnitTest` has compiled and run (1732 tests); `assembleDebug` not reported yet
(the cloud sandbox cannot reach dl.google.com, so it cannot build at all).

## Project facts
| Item | Value | Source |
|---|---|---|
| App module | `:app` (applicationId `excp.rikkahub`, debug → `excp.rikkahub.debug`) | `app/build.gradle.kts` |
| Variant | `debug` (no product flavors; debug keystore, no signing setup needed) | |
| Gradle wrapper | 9.5.0 | `gradle/wrapper/gradle-wrapper.properties` |
| AGP / Kotlin / KSP | 9.3.1 / 2.4.10 / 2.3.10 | `gradle/libs.versions.toml` |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 | `app/`, `build-logic/` |
| Java/Kotlin target | 17 | |
| Native | CMake 3.22.1 (`:llama-cpp`, `:workspace`); NDK not pinned → AGP default | |
| ABIs | arm64-v8a, x86_64 (ABI splits + universal APK) | |

## Prerequisites
- JDK 17 or newer to run Gradle 9.5 / AGP 9.3 (JDK 21 is fine): `sudo apt install openjdk-21-jdk`
- Android SDK (Android Studio or cmdline-tools) with:
  `sdkmanager "platform-tools" "platforms;android-37" "cmake;3.22.1"` and accepted licenses
  (`sdkmanager --licenses`). Build-tools and the default NDK are auto-installed by AGP when
  licenses are accepted; install them via sdkmanager if the build says otherwise.
- web-ui toolchain (the `:web` module runs `bun install` + `pnpm run build` on every `preBuild`):
  Node.js (≥ 20), [bun](https://bun.sh), [pnpm](https://pnpm.io) on `PATH`.
- Git submodules — **not initialized in a plain clone**, the build fails without them:
  `material3/material-color-utilities`, `llama-cpp/native/llama.cpp`.

## Build
```bash
git clone -b claude/sweet-euler-700yoc https://github.com/fmenesini/rikkahub-agent-VIVOX300.git
cd rikkahub-agent-VIVOX300
git submodule update --init --recursive
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # or export ANDROID_HOME
./gradlew :app:assembleDebug
```

## Debug signing and name
Debug builds are signed with `app/debug.keystore` (committed, debug only, password `android`,
SHA-256 239d6297…af03), so APKs from GitHub Actions, the sandbox and the Dell all install over
each other. The debug app is labelled **Rikka-mene** (`app/src/debug/res/values*/strings.xml`).
Debug APKs signed before this key (release apk-f5f3010-run2 and older) must be uninstalled once.

## Output
`app/build/outputs/apk/debug/`
- `app-arm64-v8a-debug.apk` ← install this on the Vivo X300
- `app-universal-debug.apk`, `app-x86_64-debug.apk`

```bash
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

## Checks after the first build
- Unit tests: `./gradlew :ai:testDebugUnitTest :workspace:testDebugUnitTest :app:testDebugUnitTest`
  (also with `JAVA_TOOL_OPTIONS="-Duser.language=it -Duser.country=IT"`: formatting must not
  depend on the machine locale)
- No Android SDK at hand: `bash scripts/host-test/run.sh` (pure-Kotlin subset + scenarios)
- First real compile of sprint-1 code. Only symbol verified on docs alone:
  `Candidate.FinishReason.MAX_TOKENS` in `ai/.../providers/AICoreProvider.kt`. If it does not
  resolve, look up the MAX_TOKENS constant in the `genai-prompt` 1.0.0-beta2 AAR and fix that line.
- Then run the Vivo checklist in `AGENT_PROJECT_MEMORY.md`.
