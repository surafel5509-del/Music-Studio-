package com.wavestudio.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.wavestudio.app.engine.AudioEngineController
import com.wavestudio.app.engine.MediaInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

class ImportException(message: String) : Exception(message)

data class ImportedAudio(val file: File, val displayName: String, val info: MediaInfo)

/**
 * Brings an audio file picked through the Storage Access Framework into the project's media folder as a WAV
 * the native engine can read. WAV files are copied byte-for-byte (no quality loss). Anything else the device's
 * decoders understand (MP3, AAC/M4A, OGG, FLAC, ...) is decoded in a streaming fashion to 16-bit PCM WAV, so memory
 * use does not depend on the file length. All work uses the platform decoders: no network, no extra libraries.
 */
class AudioImporter(private val ctx: Context, private val engine: AudioEngineController) {

    suspend fun import(uri: Uri, mediaDir: File): ImportedAudio = withContext(Dispatchers.IO) {
        mediaDir.mkdirs()
        val display = displayName(uri)
        val base = sanitize(display.substringBeforeLast('.', display)).ifBlank { "audio" }
        val stamp = System.currentTimeMillis()
        val part = File(mediaDir, "import_$stamp.part")
        val out = File(mediaDir, "${base}_$stamp.wav")
        try {
            val copied = ctx.contentResolver.openInputStream(uri)?.use { i -> part.outputStream().use { o -> i.copyTo(o) } }
                ?: throw ImportException("Cannot open the selected file")
            if (copied <= 0L) throw ImportException("The selected file is empty")
            coroutineContext.ensureActive()

            val direct = engine.mediaInfo(part.absolutePath)
            if (direct.ok && direct.frames > 0) {
                if (!part.renameTo(out)) throw ImportException("Cannot store the imported file")
                return@withContext ImportedAudio(out, display, direct)
            }
            decodeToWav(part, out)
            val info = engine.mediaInfo(out.absolutePath)
            if (!info.ok || info.frames <= 0) throw ImportException("The decoded audio could not be read back (error ${info.error})")
            ImportedAudio(out, display, info)
        } catch (t: Throwable) {
            out.delete()
            throw if (t is ImportException || t is kotlinx.coroutines.CancellationException) t
            else ImportException(t.message ?: "Unsupported or damaged audio file")
        } finally {
            part.delete()
        }
    }

    private fun displayName(uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "audio"
    } catch (_: Exception) { uri.lastPathSegment ?: "audio" }

    private fun sanitize(s: String) = s.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(40)

    private suspend fun decodeToWav(src: File, dst: File) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(src.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
            }
            if (track < 0 || format == null) throw ImportException("No audio track found (unsupported format?)")
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            var dataBytes = 0L
            val bufInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idle = 0
            var scratch = ByteArray(1 shl 16)

            RandomAccessFile(dst, "rw").use { raf ->
                raf.setLength(0)
                raf.write(ByteArray(44))
                while (!outputDone) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val ii = codec.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val ib = codec.getInputBuffer(ii)!!
                            val n = extractor.readSampleData(ib, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(bufInfo, 10_000)
                    when {
                        oi >= 0 -> {
                            idle = 0
                            val ob = codec.getOutputBuffer(oi)!!
                            if (bufInfo.size > 0) {
                                ob.position(bufInfo.offset); ob.limit(bufInfo.offset + bufInfo.size)
                                ob.order(ByteOrder.LITTLE_ENDIAN)
                                if (pcmFloat) {
                                    val fb = ob.asFloatBuffer()
                                    val n = fb.remaining()
                                    if (scratch.size < n * 2) scratch = ByteArray(n * 2)
                                    val sb = ByteBuffer.wrap(scratch).order(ByteOrder.LITTLE_ENDIAN)
                                    for (k in 0 until n) {
                                        val v = (fb.get() * 32767f).coerceIn(-32768f, 32767f)
                                        sb.putShort(Math.round(v).toShort())
                                    }
                                    raf.write(scratch, 0, n * 2); dataBytes += n * 2
                                } else {
                                    val n = ob.remaining()
                                    if (scratch.size < n) scratch = ByteArray(n)
                                    ob.get(scratch, 0, n)
                                    raf.write(scratch, 0, n); dataBytes += n
                                }
                                if (dataBytes > 0xF000_0000L) throw ImportException("Decoded audio is larger than a WAV file can hold")
                            }
                            codec.releaseOutputBuffer(oi, false)
                            if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                        oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val nf = codec.outputFormat
                            rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmFloat = nf.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                nf.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        }
                        oi == MediaCodec.INFO_TRY_AGAIN_LATER -> if (inputDone && ++idle > 500) throw ImportException("The decoder stopped responding")
                    }
                }
                if (dataBytes == 0L) throw ImportException("The file contains no decodable audio")
                if (channels < 1 || channels > 2) throw ImportException("Only mono and stereo files can be imported ($channels channels found)")
                writeHeader(raf, rate, channels, dataBytes)
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private fun writeHeader(raf: RandomAccessFile, rate: Int, channels: Int, dataBytes: Long) {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + dataBytes).toInt()); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(channels.toShort())
        h.putInt(rate); h.putInt(rate * channels * 2); h.putShort((channels * 2).toShort()); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataBytes.toInt())
        raf.seek(0); raf.write(h.array()); raf.fd.sync()
    }
}
