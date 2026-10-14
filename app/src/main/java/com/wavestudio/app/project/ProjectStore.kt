package com.wavestudio.app.project

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * On-disk layout of one project directory:
 *   project.json        current document
 *   project.json.bak    previous good document (kept on every save)
 *   media/              imported audio (WAV, never modified after import)
 *   recordings/         takes written by the native recorder (+ `<take>.take.json` sidecars)
 *
 * Saves are atomic: the new document is written to a temp file, fsynced and then renamed over
 * `project.json`; the previous file is first moved to `.bak`. A crash at any point leaves either
 * the old or the new document readable.
 */
class ProjectStore(val dir: File) {

    val projectFile get() = File(dir, "project.json")
    val backupFile get() = File(dir, "project.json.bak")
    private val tmpFile get() = File(dir, "project.json.tmp")
    val mediaDir get() = File(dir, "media")
    val recordingsDir get() = File(dir, "recordings")

    enum class Source { PRIMARY, BACKUP, NEW }

    data class LoadResult(val doc: ProjectDoc, val source: Source, val warnings: List<String>)

    class LoadError(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun ensureDirs() { dir.mkdirs(); mediaDir.mkdirs(); recordingsDir.mkdirs() }

    @Synchronized
    fun save(doc: ProjectDoc) {
        ensureDirs()
        val text = ProjectCodec.encode(doc)
        FileOutputStream(tmpFile).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        // Validate what we are about to promote: never replace a good file with an unreadable one.
        ProjectCodec.decode(tmpFile.readText(Charsets.UTF_8))
        if (projectFile.exists()) {
            Files.move(projectFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Files.move(tmpFile.toPath(), projectFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    @Synchronized
    fun load(): LoadResult {
        val warnings = ArrayList<String>()
        if (!projectFile.exists() && !backupFile.exists()) return LoadResult(ProjectDoc(), Source.NEW, warnings)
        var primaryError: Throwable? = null
        if (projectFile.exists()) {
            try {
                return LoadResult(ProjectCodec.decode(projectFile.readText(Charsets.UTF_8)), Source.PRIMARY, warnings)
            } catch (v: UnsupportedProjectVersion) {
                throw LoadError(v.message ?: "unsupported project version", v)   // do not fall back: would overwrite newer data
            } catch (t: Throwable) {
                primaryError = t
                warnings += "project.json was unreadable (${t.message}); restored from backup"
            }
        }
        if (backupFile.exists()) {
            try {
                return LoadResult(ProjectCodec.decode(backupFile.readText(Charsets.UTF_8)), Source.BACKUP, warnings)
            } catch (t: Throwable) {
                throw LoadError("Both project.json and its backup are unreadable", t)
            }
        }
        throw LoadError("project.json is unreadable and there is no backup", primaryError)
    }

    fun resolve(relative: String): File = File(dir, relative)

    /** Relative paths of clips whose media file is missing or empty. */
    fun missingMedia(doc: ProjectDoc): Set<String> =
        doc.clips.map { it.media }.distinct().filter { val f = resolve(it); !f.isFile || f.length() < 44 }.toSet()

    // ---------------------------------------------------------------- recording crash recovery

    data class PendingTake(val wav: File, val sidecar: File?, val trackId: Int, val startFrame: Long)

    /** Written before a recording starts so an interrupted take can be placed exactly where it was made. */
    fun beginTake(wavName: String, trackId: Int, startFrame: Long) {
        recordingsDir.mkdirs()
        val f = File(recordingsDir, "$wavName.take.json")
        val tmp = File(recordingsDir, "$wavName.take.json.tmp")
        FileOutputStream(tmp).use { it.write(Json.write(mapOf("trackId" to trackId, "startFrame" to startFrame)).toByteArray()); it.fd.sync() }
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun finishTake(wavName: String) { File(recordingsDir, "$wavName.take.json").delete() }

    /** WAV files in recordings/ that no clip references (a crash or kill happened before they were committed). */
    fun orphanedTakes(doc: ProjectDoc): List<PendingTake> {
        val referenced = doc.clips.map { it.media.replace('\\', '/') }.toSet()
        val wavs = recordingsDir.listFiles { f -> f.isFile && f.name.endsWith(".wav", ignoreCase = true) } ?: return emptyList()
        return wavs.sortedBy { it.name }.filter { "recordings/${it.name}" !in referenced && it.length() > 44 }.map { wav ->
            val side = File(recordingsDir, "${wav.name}.take.json")
            var track = 0; var start = 0L
            if (side.isFile) runCatching {
                val m = Json.parseObject(side.readText())
                track = m.int("trackId"); start = m.long("startFrame")
            }
            PendingTake(wav, side.takeIf { it.isFile }, track, start)
        }
    }
}
