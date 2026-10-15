package com.wavestudio.app.io

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Extract audio track from any video (MP4/MKV/WebM/3GP) or audio file and write
 * a 16-bit PCM WAV file suitable for loading into the native engine via loadWav().
 *
 * Works offline using only Android framework classes (no internet dependency).
 */
object VideoAudioExtractor {

    data class Result(val pcmPath: String, val sampleRate: Int, val channels: Int, val frames: Long)

    fun extractToWav(context: Context, inputPath: String, outWav: String): Result? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(inputPath)
            var audioIdx = -1
            var audioFmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { audioIdx = i; audioFmt = f; break }
            }
            if (audioIdx < 0 || audioFmt == null) return null
            ex.selectTrack(audioIdx)
            val sr = audioFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = audioFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
            val mime = audioFmt.getString(MediaFormat.KEY_MIME) ?: return null

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFmt, null, null, 0)
            codec.start()
            val tmpPcm = File(context.cacheDir, "extracted_${System.currentTimeMillis()}.pcm")
            val os = FileOutputStream(tmpPcm)
            var inputDone = false
            var totalSamples = 0L
            val bufInfo = MediaCodec.BufferInfo()
            val outBuf = ShortArray(8192)

            while (true) {
                if (!inputDone) {
                    val iidx = codec.dequeueInputBuffer(10_000)
                    if (iidx >= 0) {
                        val ib = codec.getInputBuffer(iidx) ?: continue
                        ib.clear()
                        val sz = ex.readSampleData(ib, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(iidx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(iidx, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oidx = codec.dequeueOutputBuffer(bufInfo, 10_000)
                if (oidx >= 0) {
                    val ob = codec.getOutputBuffer(oidx) ?: continue
                    val is16 = true // assume PCM16 out from decoder
                    var pos = ob.position(); var lim = ob.limit()
                    ob.order(ByteOrder.LITTLE_ENDIAN)
                    val sbuf = ob.asShortBuffer()
                    val n = sbuf.remaining()
                    val toRead = minOf(n, outBuf.size)
                    for (k in 0 until toRead) outBuf[k] = sbuf.get()
                    val bytes = ByteArray(toRead * 2)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outBuf, 0, toRead)
                    os.write(bytes)
                    totalSamples += toRead / ch
                    codec.releaseOutputBuffer(oidx, false)
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (oidx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    Thread.sleep(5)
                    if (totalSamples > 0 && bufInfo.size == 0 && bufInfo.presentationTimeUs > 0) break
                }
                if (inputDone && oidx < 0 && totalSamples > 0) { /* drain */ }
            }
            codec.stop(); codec.release(); os.close()
            // Write WAV header
            val outF = File(outWav)
            val wos = FileOutputStream(outF)
            writeWavHeader(wos, sr, ch, totalSamples.toInt())
            FileInputStream(tmpPcm).use { it.copyTo(wos) }
            wos.close()
            tmpPcm.delete()
            return Result(outWav, sr, ch, totalSamples)
        } catch (t: Throwable) {
            t.printStackTrace()
            return null
        } finally {
            try { ex.release() } catch (_: Throwable) {}
        }
    }

    private fun writeWavHeader(os: FileOutputStream, sr: Int, ch: Int, numSamples: Int) {
        val dataSize = numSamples * ch * 2
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + dataSize)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1) // PCM
        bb.putShort(ch.toShort())
        bb.putInt(sr)
        bb.putInt(sr * ch * 2)
        bb.putShort((ch * 2).toShort())
        bb.putShort(16)
        bb.put("data".toByteArray())
        bb.putInt(dataSize)
        os.write(bb.array())
    }

    private fun appendFile(src: File, os: FileOutputStream) {
        FileInputStream(src).use { it.copyTo(os) }
    }
}
