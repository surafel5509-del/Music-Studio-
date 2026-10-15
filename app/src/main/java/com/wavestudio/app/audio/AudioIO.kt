package com.wavestudio.app.audio

import android.net.Uri
import com.wavestudio.app.domain.Project
import java.io.File

/**
 * Audio import/export and offline codec handling. Uses Android MediaExtractor/MediaCodec
 * (available offline on Android 8+). No internet required.
 */
interface AudioIO {
    suspend fun loadAudio(uri: Uri): LoadedAudio?
    suspend fun loadAudio(path: String): LoadedAudio?
    suspend fun exportWav(
        project: Project,
        destination: File,
        bitDepth: Int = 24,
        progressCallback: (Float) -> Unit = {}
    ): Boolean
    suspend fun exportCompressed(
        project: Project,
        destination: File,
        mimeType: String,
        bitrate: Int,
        progressCallback: (Float) -> Unit = {}
    ): Boolean
    suspend fun exportVideo(
        project: Project,
        destination: File,
        progressCallback: (Float) -> Unit = {}
    ): Boolean
    suspend fun writeMetadata(file: File, metadata: AudioMetadata): Boolean
}

data class LoadedAudio(
    val path: String,
    val sampleRate: Int,
    val channels: Int,
    val frames: Long,
    val bitDepth: Int,
    val durationMs: Long,
    val floatData: FloatArray? = null // Optional; for short files the engine uses streaming
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LoadedAudio) return false
        return path == other.path
    }
    override fun hashCode(): Int = path.hashCode()
}

data class AudioMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val comment: String? = null,
    val trackNumber: Int? = null,
    val albumArtBytes: ByteArray? = null
)
