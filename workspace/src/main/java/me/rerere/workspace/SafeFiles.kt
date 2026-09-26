package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Like [File.deleteRecursively] but never follows symbolic links: a link is deleted, the
 * tree it points to is left alone. Kotlin's version walks with File.isDirectory, which
 * follows links, so deleting a folder holding `evil -> /data/user/0/<pkg>/databases`
 * wipes the target. Anything that can write into an agent-reachable folder (the PRoot
 * shell's `ln -s`, an extracted archive, a git checkout) can plant such a link.
 */
fun File.deleteRecursivelyNoFollow(): Boolean {
    val start = toPath()
    if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return true
    var ok = true
    fun remove(path: Path) {
        ok = try {
            Files.deleteIfExists(path)
            true
        } catch (_: IOException) {
            false
        } && ok
    }
    Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            remove(file)
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            remove(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            remove(dir)
            return FileVisitResult.CONTINUE
        }
    })
    return ok
}

/** True if this path itself is a symbolic link (not whether its target is). */
val File.isSymlink: Boolean get() = Files.isSymbolicLink(toPath())
