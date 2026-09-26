package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A symlink planted inside the workspace (PRoot `ln -s`, archive, git checkout) must never
 * let file operations reach outside it. Regression for delete(recursive) wiping the link
 * target and tree() listing files outside the root.
 */
class WorkspaceSymlinkEscapeTest {
    private val base = Files.createTempDirectory("ws-symlink").toFile()
    private val root = File(base, "root").apply { mkdirs() }
    private val outside = File(base, "private").apply { mkdirs() }
    private val secret = File(outside, "secret.db").apply { writeText("chats") }
    private val fs = WorkspaceFileSystem()

    private fun link(at: String, to: File) {
        val f = File(root, at)
        f.parentFile.mkdirs()
        Files.createSymbolicLink(f.toPath(), to.toPath())
    }

    @Test
    fun `recursive delete removes the link, not its target`() {
        link("build/evil", outside)
        assertTrue(fs.delete(root, "build", recursive = true))
        assertTrue(secret.exists())
        assertFalse(File(root, "build").exists())
    }

    @Test
    fun `move overwriting a directory does not follow links inside it`() {
        link("dst/evil", outside)
        fs.writeText(root, "src/a.txt", "a")
        fs.move(root, "src", "dst", overwrite = true)
        assertTrue(secret.exists())
        assertEquals("a", fs.readText(root, "dst/a.txt"))
    }

    @Test
    fun `tree does not descend through links`() {
        link("sub/evil", outside)
        val paths = fs.tree(root).entries.map { it.path }
        assertTrue(paths.none { it.contains("secret") || it.startsWith("..") })
    }

    @Test
    fun `direct access through a link is rejected`() {
        link("direct", outside)
        val error = runCatching { fs.readText(root, "direct/secret.db") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("escapes workspace root"))
        assertTrue(runCatching { fs.writeText(root, "direct/new.txt", "x") }.isFailure)
        assertFalse(File(outside, "new.txt").exists())
    }

    @Test
    fun `dot-dot and absolute paths stay inside the root`() {
        assertTrue(runCatching { fs.readText(root, "../private/secret.db") }.isFailure)
        assertTrue(runCatching { fs.readText(root, "a/../../private/secret.db") }.isFailure)
        // A leading slash is treated as workspace-relative, never as a host path.
        assertTrue(runCatching { fs.readText(root, secret.absolutePath) }.isFailure)
    }

    @Test
    fun `helper deletes a link to a directory without touching the target`() {
        link("l", outside)
        assertTrue(File(root, "l").deleteRecursivelyNoFollow())
        assertTrue(secret.exists())
    }
}
