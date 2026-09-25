package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * On a device /data/data is a symlink to /data/user/0, so the guard sees canonical
 * /data/user/<n>/<pkg> paths. These cases use those forms directly (a JVM host has no such
 * symlink, which is how the old /data/data-only check passed its tests while being dead
 * on the phone).
 */
class PathSafetyGuardDevicePathsTest {
    private val own = "me.rerere.rikkahub"
    private fun check(path: String) = checkPathSafety(path, own)

    @Test fun `other app sandbox is blocked in canonical device form`() {
        assertNotNull(check("/data/user/0/com.whatsapp/databases/msgstore.db"))
        assertNotNull(check("/data/user/10/com.whatsapp/files"))
        assertNotNull(check("/data/user_de/0/com.android.providers.telephony/databases/mmssms.db"))
    }

    @Test fun `own secrets are blocked in every form`() {
        for (root in listOf("/data/data/$own", "/data/user/0/$own", "/data/user_de/0/$own")) {
            assertNotNull(check("$root/files/datastore/settings.preferences_pb"))
            assertNotNull(check("$root/databases/rikka_hub.db"))
            assertNotNull(check("$root/databases"))
            assertNotNull(check("$root/shared_prefs/skill_secrets.xml"))
            assertNotNull(check("$root/no_backup/gemini_accounts"))
            assertNotNull(check("$root/app_webview/Default/Cookies"))
            assertNotNull(check("$root/files/known_hosts"))
        }
    }

    @Test fun `own working areas stay reachable`() {
        assertNull(check("/data/user/0/$own/files/workspaces/abc/notes.md"))
        assertNull(check("/data/user/0/$own/files/upload/photo.jpg"))
        assertNull(check("/data/user/0/$own/files/tool_outputs/c1.txt"))
        assertNull(check("/data/user/0/$own/cache/tmp.bin"))
        assertNull(check("/data/user/0/$own"))
        // A sibling whose name merely starts with a blocked one is not blocked.
        assertNull(check("/data/user/0/$own/files/datastore_export.json"))
    }

    @Test fun `package-name prefix tricks do not count as own sandbox`() {
        assertNotNull(check("/data/user/0/$own.evil/files/x"))
        assertNotNull(check("/data/user/0/${own}x/files/x"))
    }
}
