package com.wavestudio.app.engine

import com.wavestudio.app.project.EngineApi
import com.wavestudio.app.project.NativeEffectInfo

/** [EngineApi] implemented over JNI. */
class NativeEngineApi(private val n: NativeAudioEngine) : EngineApi {
    override fun resetProject() = n.nativeResetProject()
    override fun addTrack(id: Int) = n.nativeAddTrack(id)
    override fun removeTrack(id: Int) = n.nativeRemoveTrack(id)
    override fun setTrackVolume(id: Int, v: Float) = n.nativeSetTrackVolume(id, v)
    override fun setTrackPan(id: Int, v: Float) = n.nativeSetTrackPan(id, v)
    override fun setTrackMute(id: Int, m: Boolean) = n.nativeSetTrackMute(id, m)
    override fun setTrackSolo(id: Int, s: Boolean) = n.nativeSetTrackSolo(id, s)
    override fun setClip(id: Int, trackId: Int, path: String, start: Long, offset: Long, length: Long,
                         gainDb: Float, fadeIn: Long, fadeOut: Long) =
        n.nativeSetClip(id, trackId, path, start, offset, length, gainDb, fadeIn, fadeOut)
    override fun removeClip(id: Int) = n.nativeRemoveClip(id)
    override fun addEffect(trackId: Int, type: String, index: Int) = n.nativeAddEffect(trackId, type, index)
    override fun removeEffect(nativeId: Int) = n.nativeRemoveEffect(nativeId)
    override fun setEffectParam(nativeId: Int, index: Int, value: Float) = n.nativeSetEffectParam(nativeId, index, value)
    override fun setEffectBypass(nativeId: Int, bypass: Boolean) = n.nativeSetEffectBypass(nativeId, bypass)
    override fun effectInfo(nativeId: Int): NativeEffectInfo? = NativeEffectInfo.parse(n.nativeEffectInfo(nativeId))
    override fun setMasterVolume(v: Float) = n.nativeSetMasterVolume(v)
    override fun setLoop(start: Long, end: Long, enabled: Boolean) = n.nativeSetLoop(start, end, enabled)
    override fun setMetronome(on: Boolean, bpm: Float, beats: Int) = n.nativeSetMetronome(on, bpm, beats)
    override fun setCountIn(bars: Int) = n.nativeSetCountIn(bars)
}
