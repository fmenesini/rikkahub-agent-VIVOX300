package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for issue #42: launch_activity had no approval gate while its sibling launch_app
 * did. Asserts the fix without re-testing the whole [ToolApprovalDefaults] set.
 */
class ToolApprovalDefaultsTest {

    @Test
    fun `launch_activity requires approval, same as launch_app`() {
        assertTrue(ToolApprovalDefaults.requiresApproval("launch_activity"))
        assertTrue(ToolApprovalDefaults.allowsAlwaysAllow("launch_activity"))
    }

    @Test
    fun `every tool that sends a model-chosen URL off the device requires approval`() {
        // A URL is a data channel (…/?d=<secret>). These are all the model-callable HTTP GETs.
        for (name in listOf("web_fetch", "web_extract", "browser_open", "scrape_web", "open_url")) {
            assertTrue("$name must be approval-gated", ToolApprovalDefaults.requiresApproval(name))
        }
    }

    @Test
    fun `reading other apps' notifications is gated like reading SMS`() {
        for (name in listOf("list_sms_inbox", "list_recent_notifications", "list_active_notifications")) {
            assertTrue("$name must be approval-gated", ToolApprovalDefaults.requiresApproval(name))
        }
    }
}
