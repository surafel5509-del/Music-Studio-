package com.wavestudio.app.project

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Whole-directory operations on project folders (pure JVM so they can be unit-tested). */
object ProjectFiles {

    /** Deletes [dir] and everything in it. Returns false if something could not be removed. A missing directory counts as success. */
    fun deleteProject(dir: File): Boolean = !dir.exists() || dir.deleteRecursively()

    /**
     * Copies a project folder. Audio under media/ and recordings/ is never modified after it is written, so those files are
     * hard-linked when the file system allows it (no extra storage, instant) and copied otherwise. `project.json`
     * is always a real copy. Temporary files and recovery sidecars of the source are not copied.
     * On failure the partial destination is removed and [IOException] is thrown.
     */
    fun duplicateProject(src: File, dst: File) {
        if (!src.isDirectory) throw IOException("Project folder not found")
        if (dst.exists()) throw IOException("Destination exists")
        try {
            src.walkTopDown().forEach { f ->
                val rel = f.relativeTo(src)
                val target = File(dst, rel.path)
                when {
                    f.isDirectory -> target.mkdirs()
                    f.name.endsWith(".tmp") || f.name.endsWith(".take.json") -> {}
                    else -> {
                        target.parentFile?.mkdirs()
                        val immutableMedia = rel.path.startsWith("media") || rel.path.startsWith("recordings")
                        var linked = false
                        if (immutableMedia) {
                            try { Files.createLink(target.toPath(), f.toPath()); linked = true } catch (_: Exception) {}
                        }
                        if (!linked) Files.copy(f.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
            }
        } catch (t: Throwable) {
            dst.deleteRecursively()
            throw if (t is IOException) t else IOException(t.message, t)
        }
    }

    /** Total size in bytes of the files under [dir]. */
    fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
