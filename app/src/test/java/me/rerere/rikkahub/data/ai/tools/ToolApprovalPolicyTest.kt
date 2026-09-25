package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults.Decision
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults.RunKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The approval decision the agent loop actually executes (ToolApprovalDefaults.decide +
 * autoApproves), per run kind. Principle under test: a grant never widens across contexts.
 */
class ToolApprovalPolicyTest {

    private fun auto(
        tool: String,
        kind: RunKind = RunKind.INTERACTIVE,
        yolo: Boolean = false,
        chat: Boolean = false,
        parentChat: Boolean = false,
        always: Boolean = false,
    ) = ToolApprovalDefaults.autoApproves(tool, kind, yolo, chat, parentChat, always)

    private fun decide(tool: String, kind: RunKind, autoApproved: Boolean) =
        ToolApprovalDefaults.decide(tool, ToolApprovalDefaults.requiresApproval(tool), kind) { autoApproved }

    private fun decideLive(tool: String, kind: RunKind, parentChat: Boolean = false, chat: Boolean = false) =
        decide(tool, kind, auto(tool, kind, chat = chat, parentChat = parentChat))

    // 1. parent (interactive) approval
    @Test fun `interactive chat prompts for gated tools and runs free ones`() {
        assertEquals(Decision.Prompt, decideLive("scrape_web", RunKind.INTERACTIVE))
        assertEquals(Decision.Run, decideLive("search_web", RunKind.INTERACTIVE))
        assertEquals(Decision.Run, decideLive("scrape_web", RunKind.INTERACTIVE, chat = true))
    }

    // 2 + 9 + 10. sub-agent approval = inheritance of the parent's grants, never more
    @Test fun `sub-agent cannot use a gated tool the parent chat never allowed`() {
        val d = decideLive("scrape_web", RunKind.DELEGATED)
        assertTrue(d is Decision.Deny)
        assertTrue((d as Decision.Deny).reason.startsWith("not_authorised_for_this_run"))
        assertTrue(decideLive("list_recent_notifications", RunKind.DELEGATED) is Decision.Deny)
    }

    @Test fun `sub-agent inherits exactly the parent's grants`() {
        assertEquals(Decision.Run, decideLive("scrape_web", RunKind.DELEGATED, parentChat = true))
        // A grant in the sub-agent's own chat id is not a parent grant.
        assertTrue(decideLive("scrape_web", RunKind.DELEGATED, chat = true) is Decision.Deny)
        // Ungated tools keep working.
        assertEquals(Decision.Run, decideLive("search_web", RunKind.DELEGATED))
    }

    @Test fun `the approved dispatch itself grants nothing to the sub-agent`() {
        // Approving subagent_dispatch in the parent is a grant for that one call only; the
        // sub-agent's own egress still needs a parent grant.
        assertTrue(decideLive("web_fetch", RunKind.DELEGATED) is Decision.Deny)
    }

    // 3. NO_ALWAYS_ALLOW has runtime meaning on every path
    @Test fun `NO_ALWAYS_ALLOW is never auto-approved`() {
        for (tool in ToolApprovalDefaults.NO_ALWAYS_ALLOW) {
            for (kind in RunKind.entries) {
                assertFalse("$tool $kind yolo", auto(tool, kind, yolo = true))
                assertFalse("$tool $kind always", auto(tool, kind, always = true))
                assertFalse("$tool $kind chat", auto(tool, kind, chat = true, parentChat = true))
            }
            assertEquals(Decision.Prompt, decideLive(tool, RunKind.INTERACTIVE, chat = true))
        }
    }

    // 4. Allow for this chat
    @Test fun `allow for this chat covers ordinary gated tools only`() {
        assertTrue(auto("ssh_exec", chat = true))
        assertFalse(auto("eval_javascript", chat = true))
        assertFalse(auto("keystore_decrypt", chat = true))
    }

    // 5. headless (cron / external automation / skill tester)
    @Test fun `unattended runs keep auto-approval but never run NO_ALWAYS_ALLOW tools`() {
        assertEquals(Decision.Run, decideLive("scrape_web", RunKind.UNATTENDED))
        assertEquals(Decision.Run, decideLive("termux_run_command", RunKind.UNATTENDED))
        for (tool in ToolApprovalDefaults.NO_ALWAYS_ALLOW) {
            val d = decideLive(tool, RunKind.UNATTENDED)
            assertTrue("$tool", d is Decision.Deny && d.reason.startsWith("requires_human_approval"))
        }
    }

    // 6. YOLO
    @Test fun `YOLO still auto-approves ordinary gated tools`() {
        assertTrue(auto("ssh_exec", yolo = true))
        assertTrue(auto("scrape_web", RunKind.DELEGATED, yolo = true))
        assertFalse(auto("mcp_add", yolo = true))
    }

    // 7. memory write
    @Test fun `memory cannot be written from runs nobody watches`() {
        // memory_tool needs no approval interactively (unchanged)...
        assertEquals(Decision.Run, decide("memory_tool", RunKind.INTERACTIVE, autoApproved = false))
        // ...but is denied unattended and in sub-agents, even under YOLO.
        for (kind in listOf(RunKind.UNATTENDED, RunKind.DELEGATED)) {
            val d = decide("memory_tool", kind, autoApproved = true)
            assertTrue("$kind", d is Decision.Deny && d.reason.startsWith("memory_disabled_unattended"))
        }
    }

    // 8. network egress chain from the Sprint 3 audit
    @Test fun `notification to network chain needs a human or a parent grant at each hop`() {
        for (hop in listOf("list_recent_notifications", "scrape_web")) {
            assertEquals(hop, Decision.Prompt, decideLive(hop, RunKind.INTERACTIVE))
            assertTrue(hop, decideLive(hop, RunKind.DELEGATED) is Decision.Deny)
        }
    }
}

/** The registry that tells ChatService which RunKind a conversation has. */
class HeadlessRunKindTest {
    @Test fun `each marking maps to the right run kind and unmark resets it`() {
        val cron = kotlin.uuid.Uuid.random()
        val sub = kotlin.uuid.Uuid.random()
        val parent = kotlin.uuid.Uuid.random()
        val telegram = kotlin.uuid.Uuid.random()
        HeadlessConversations.mark(cron)
        HeadlessConversations.markDelegated(sub, parent)
        HeadlessConversations.markBrowserHeadless(telegram)
        assertEquals(RunKind.UNATTENDED, HeadlessConversations.runKind(cron))
        assertEquals(RunKind.DELEGATED, HeadlessConversations.runKind(sub))
        assertEquals(RunKind.INTERACTIVE, HeadlessConversations.runKind(telegram))
        assertEquals(RunKind.INTERACTIVE, HeadlessConversations.runKind(parent))
        assertEquals(parent, HeadlessConversations.delegationParent(sub))
        // A sub-agent is headless for the recursion guard but NOT blanket auto-approved.
        assertTrue(HeadlessConversations.isHeadless(sub))
        assertFalse(HeadlessConversations.shouldAutoApprove(sub))
        HeadlessConversations.unmark(sub)
        assertEquals(RunKind.INTERACTIVE, HeadlessConversations.runKind(sub))
        HeadlessConversations.unmark(cron)
        HeadlessConversations.unmark(telegram)
    }

    @Test fun `sub-agent with unknown parent inherits nothing`() {
        val sub = kotlin.uuid.Uuid.random()
        HeadlessConversations.markDelegated(sub, null)
        assertEquals(RunKind.DELEGATED, HeadlessConversations.runKind(sub))
        assertEquals(null, HeadlessConversations.delegationParent(sub))
        HeadlessConversations.unmark(sub)
    }
}
