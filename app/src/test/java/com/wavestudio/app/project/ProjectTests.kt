package com.wavestudio.app.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JsonTest {
    @Test fun roundTripsNestedValues() {
        val v = mapOf("a" to 1, "b" to listOf(1.5, "x\"y\n", null, true), "c" to mapOf("d" to -3L))
        val back = Json.parse(Json.write(v)) as Map<*, *>
        assertEquals(1L, back["a"])
        assertEquals(listOf(1.5, "x\"y\n", null, true), back["b"])
        assertEquals(-3L, (back["c"] as Map<*, *>)["d"])
    }

    @Test fun rejectsGarbageAndTruncation() {
        for (bad in listOf("", "{", "{\"a\":", "[1,2", "{\"a\" 1}", "nul", "{\"a\":1} x", "\"abc")) {
            try { Json.parse(bad); fail("accepted: $bad") } catch (_: JsonException) {}
        }
    }

    @Test fun nanIsWrittenAsValidJson() {
        assertEquals(0L, (Json.parse(Json.write(listOf(Float.NaN))) as List<*>)[0])
    }

    @Test fun deepNestingIsRejectedNotStackOverflow() {
        try { Json.parse("[".repeat(10000)); fail() } catch (_: JsonException) {}
    }
}

class ProjectCodecTest {
    private fun sample(): ProjectDoc {
        var d = ProjectDoc(name = "Song \"1\"", bpm = 97.5f, created = 5, modified = 6)
        d = EditOps.addTrack(d, "Vox").first
        val t = d.tracks[0].id
        d = EditOps.updateTrack(d, t) { it.copy(volume = 0.8f, pan = -0.25f, mute = true) }
        d = EditOps.addClip(d, t, "media/a.wav", "a", 48000, 96000, 144000).first
        d = EditOps.addEffect(d, t, "eq").first
        d = EditOps.addEffect(d, 0, "limiter").first
        d = EditOps.addBus(d, "Verb").first
        d = EditOps.addEffect(d, -1, "reverb").first
        d = EditOps.setTrackSend(d, t, 0, 0.4f)
        d = EditOps.setBusLevel(d, 0, 0.75f)
        val fx = d.tracks[0].effects[0].id
        d = EditOps.setEffectParam(d, fx, "b1_gain", 3.5f)
        return d.copy(loopStart = 10, loopEnd = 99999, loopEnabled = true, metronome = true, countInBars = 2,
            punchEnabled = true, punchIn = 1000, punchOut = 5000).let { EditOps.addMarker(it, 24000, "Verse").first }
    }

    @Test fun encodeDecodeIsLossless() {
        val d = sample()
        assertEquals(d, ProjectCodec.decode(ProjectCodec.encode(d)))
    }

    @Test fun versionZeroFileWithMissingFieldsStillLoads() {
        val d = ProjectCodec.decode("""{"name":"Old","tracks":[{"id":3,"name":"T"}],"clips":[{"id":4,"trackId":3,"media":"media/x.wav","start":10,"length":500}]}""")
        assertEquals("Old", d.name)
        assertEquals(1, d.tracks.size)
        assertEquals(500L, d.clips[0].sourceFrames)        // bound inferred when absent
        assertEquals(PROJECT_FORMAT_VERSION, d.version)
        assertTrue(d.nextId > 4)                            // new ids can never collide with loaded ones
    }

    @Test fun newerVersionIsRefusedNotMangled() {
        try { ProjectCodec.decode("""{"version":99,"name":"x"}"""); fail() } catch (e: UnsupportedProjectVersion) { assertEquals(99, e.found) }
    }

    @Test fun outOfRangeValuesAreClamped() {
        val d = ProjectCodec.decode("""{"bpm":100000,"masterVolume":50,"tracks":[{"id":1,"volume":99,"pan":-7}]}""")
        assertEquals(400f, d.bpm, 0f); assertEquals(2f, d.masterVolume, 0f)
        assertEquals(4f, d.tracks[0].volume, 0f); assertEquals(-1f, d.tracks[0].pan, 0f)
    }

    @Test fun clipsWithoutTrackAreKeptNotDeleted() {
        val d = ProjectCodec.decode("""{"tracks":[],"clips":[{"id":2,"trackId":9,"media":"media/y.wav","length":10}]}""")
        assertEquals(1, d.clips.size)
    }
}

class ProjectStoreTest {
    private fun tmp(): File = Files.createTempDirectory("wsproj").toFile()

    @Test fun saveThenLoad() {
        val s = ProjectStore(tmp())
        val d = EditOps.addTrack(ProjectDoc(name = "A")).first
        s.save(d)
        val r = s.load()
        assertEquals(ProjectStore.Source.PRIMARY, r.source)
        assertEquals(d, r.doc)
        assertFalse(File(s.dir, "project.json.tmp").exists())
    }

    @Test fun freshDirectoryYieldsNewProject() {
        assertEquals(ProjectStore.Source.NEW, ProjectStore(tmp()).load().source)
    }

    @Test fun corruptPrimaryFallsBackToBackup() {
        val s = ProjectStore(tmp())
        s.save(ProjectDoc(name = "v1"))
        s.save(ProjectDoc(name = "v2"))
        s.projectFile.writeText("{\"name\": \"trunc")            // simulate a torn write
        val r = s.load()
        assertEquals(ProjectStore.Source.BACKUP, r.source)
        assertEquals("v1", r.doc.name)
        assertTrue(r.warnings.isNotEmpty())
    }

    @Test fun backupIsKeptOnEverySave() {
        val s = ProjectStore(tmp())
        s.save(ProjectDoc(name = "one")); s.save(ProjectDoc(name = "two"))
        assertEquals("one", ProjectCodec.decode(s.backupFile.readText()).name)
        assertEquals("two", ProjectCodec.decode(s.projectFile.readText()).name)
    }

    @Test fun bothCorruptIsAReportedErrorNotAnEmptyProject() {
        val s = ProjectStore(tmp())
        s.save(ProjectDoc(name = "v1")); s.save(ProjectDoc(name = "v2"))
        s.projectFile.writeText("garbage"); s.backupFile.writeText("garbage")
        try { s.load(); fail() } catch (_: ProjectStore.LoadError) {}
    }

    @Test fun newerFileIsNeverOverwrittenViaFallback() {
        val s = ProjectStore(tmp())
        s.save(ProjectDoc(name = "old"))
        s.save(ProjectDoc(name = "older"))
        s.projectFile.writeText("""{"version":99}""")
        try { s.load(); fail() } catch (e: ProjectStore.LoadError) { assertTrue(e.cause is UnsupportedProjectVersion) }
    }

    @Test fun missingMediaIsDetected() {
        val s = ProjectStore(tmp()); s.ensureDirs()
        File(s.mediaDir, "ok.wav").writeBytes(ByteArray(100))
        var d = EditOps.addTrack(ProjectDoc()).first
        d = EditOps.addClip(d, d.tracks[0].id, "media/ok.wav", "ok", 0, 100).first
        d = EditOps.addClip(d, d.tracks[0].id, "media/gone.wav", "gone", 0, 100).first
        assertEquals(setOf("media/gone.wav"), s.missingMedia(d))
    }

    @Test fun interruptedRecordingIsFoundAndPlacedWhereItWasMade() {
        val s = ProjectStore(tmp()); s.ensureDirs()
        File(s.recordingsDir, "take_1.wav").writeBytes(ByteArray(5000))
        s.beginTake("take_1.wav", trackId = 7, startFrame = 123456)
        File(s.recordingsDir, "deleted_by_user.wav").writeBytes(ByteArray(5000))     // no sidecar: committed earlier, then deleted
        File(s.recordingsDir, "committed.wav").writeBytes(ByteArray(5000))
        s.beginTake("committed.wav", 1, 0)                                           // stale sidecar of a committed take
        var d = EditOps.addTrack(ProjectDoc()).first
        d = EditOps.addClip(d, d.tracks[0].id, "recordings/committed.wav", "c", 0, 10).first
        val orphans = s.orphanedTakes(d)
        assertEquals(listOf("take_1.wav"), orphans.map { it.wav.name })
        assertEquals(7, orphans[0].trackId); assertEquals(123456L, orphans[0].startFrame)
        assertFalse(File(s.recordingsDir, "committed.wav.take.json").exists())       // stale marker cleaned up
        assertTrue(File(s.recordingsDir, "deleted_by_user.wav").exists())            // never delete user audio
        s.finishTake("take_1.wav")
        assertTrue(s.orphanedTakes(d).isEmpty())
    }

    @Test fun emptyTakeIsDroppedNotRecovered() {
        val s = ProjectStore(tmp()); s.ensureDirs()
        File(s.recordingsDir, "t.wav").writeBytes(ByteArray(44))
        s.beginTake("t.wav", 1, 0)
        assertTrue(s.orphanedTakes(ProjectDoc()).isEmpty())
    }
}

class EditOpsTest {
    private fun base(): Triple<ProjectDoc, Int, Int> {
        var d = ProjectDoc()
        val (d1, t) = EditOps.addTrack(d, "T"); d = d1
        val (d2, c) = EditOps.addClip(d, t, "media/a.wav", "a", 1000, 4000, 10000); d = d2
        return Triple(d, t, c)
    }

    @Test fun splitProducesContiguousHalvesOverTheSameMedia() {
        val (d, _, c) = base()
        val (s, n) = EditOps.splitClip(d, c, 2500)
        val a = s.clip(c)!!; val b = s.clip(n)!!
        assertEquals(1000L, a.startFrame); assertEquals(1500L, a.lengthFrames)
        assertEquals(2500L, b.startFrame); assertEquals(2500L, b.lengthFrames)
        assertEquals(a.endFrame, b.startFrame)
        assertEquals(a.offsetFrames + a.lengthFrames, b.offsetFrames)                  // seamless in the source
        assertEquals(d.endFrame, s.endFrame)
    }

    @Test fun splitOutsideTheClipIsRejected() {
        val (d, _, c) = base()
        assertEquals(-1, EditOps.splitClip(d, c, 1000).second)
        assertEquals(-1, EditOps.splitClip(d, c, 5000).second)
        assertEquals(-1, EditOps.splitClip(d, 999, 2000).second)
    }

    @Test fun trimIsBoundedBySourceMedia() {
        val (d, _, c) = base()                                  // source 10000 frames, clip shows [0,4000) at 1000
        val r = EditOps.trimClip(d, c, newEnd = 999_999)
        assertEquals(10000L, r.clip(c)!!.lengthFrames)           // cannot extend past the source
        val l = EditOps.trimClip(d, c, newStart = -500)
        assertEquals(1000L, l.clip(c)!!.startFrame)              // already at source start: nothing to reveal
        val tr = EditOps.trimClip(d, c, newStart = 2000)
        assertEquals(1000L, tr.clip(c)!!.offsetFrames); assertEquals(3000L, tr.clip(c)!!.lengthFrames)
        assertEquals(d.clip(c)!!.endFrame, tr.clip(c)!!.endFrame)    // right edge unchanged
    }

    @Test fun trimNeverCreatesNegativeOrTinyClip() {
        val (d, _, c) = base()
        val r = EditOps.trimClip(d, c, newEnd = 0)
        assertTrue(r.clip(c)!!.lengthFrames >= EditOps.MIN_CLIP)
    }

    @Test fun moveClampsAtZeroAndCanChangeTrack() {
        val (d0, t, c) = base()
        val (d, t2) = EditOps.addTrack(d0)
        val m = EditOps.moveClip(d, c, -5000, t2)
        assertEquals(0L, m.clip(c)!!.startFrame); assertEquals(t2, m.clip(c)!!.trackId)
        assertEquals(t, EditOps.moveClip(d, c, 10, 12345).clip(c)!!.trackId)     // unknown target track ignored
    }

    @Test fun duplicateLandsAfterOriginal() {
        val (d, _, c) = base()
        val (s, n) = EditOps.duplicateClip(d, c)
        assertEquals(d.clip(c)!!.endFrame, s.clip(n)!!.startFrame)
        assertEquals(2, s.clips.size)
    }

    @Test fun removingTrackRemovesItsClipsAndEffects() {
        val (d, t, _) = base()
        val (e, _) = EditOps.addEffect(d, t, "gain")
        val r = EditOps.removeTrack(e, t)
        assertTrue(r.clips.isEmpty()); assertTrue(r.tracks.isEmpty())
    }

    @Test fun idsAreNeverReused() {
        var (d, _, c) = base()
        val before = d.nextId
        d = EditOps.deleteClip(d, c)
        val (d2, id) = EditOps.addClip(d, d.tracks[0].id, "media/b.wav", "b", 0, 10)
        assertTrue(id >= before)
        assertTrue(d2.nextId > id)
    }

    @Test fun noOpReturnsSameInstance() {
        val (d, _, c) = base()
        assertSame(d, EditOps.moveClip(d, c, 1000))
        assertSame(d, EditOps.deleteClip(d, 4242))
    }

    @Test fun snapToGrid() {
        assertEquals(24000L, EditOps.snap(23000, 24000.0))
        assertEquals(23000L, EditOps.snap(23000, 24000.0, enabled = false))
        assertEquals(48000L, EditOps.snap(40000, 24000.0))
    }

    @Test fun effectParamsAndBypassOnTrackAndMaster() {
        val (d0, t, _) = base()
        val (d1, fx) = EditOps.addEffect(d0, t, "compressor")
        val (d2, mfx) = EditOps.addEffect(d1, 0, "limiter")
        var d = EditOps.setEffectParam(d2, fx, "threshold", -30f)
        d = EditOps.setEffectBypass(d, mfx, true)
        assertEquals(-30f, d.tracks[0].effects[0].params["threshold"]!!, 0f)
        assertTrue(d.masterEffects[0].bypass)
        d = EditOps.removeEffect(d, fx)
        assertTrue(d.tracks[0].effects.isEmpty())
        assertEquals(1, d.masterEffects.size)
    }
}

class HistoryTest {
    @Test fun undoRedoRestoreExactDocuments() {
        val h = History<ProjectDoc>()
        val a = ProjectDoc(name = "a")
        val (b, t) = EditOps.addTrack(a)
        val c = EditOps.updateTrack(b, t) { it.copy(mute = true) }
        h.record("add track", a, b); h.record("mute", b, c)
        assertEquals("mute", h.undoLabel)
        assertSame(b, h.undo()); assertSame(a, h.undo()); assertNull(h.undo())
        assertSame(b, h.redo()); assertSame(c, h.redo()); assertNull(h.redo())
    }

    @Test fun newEditClearsRedo() {
        val h = History<Int>()
        h.record("x", 1, 2); h.undo(); assertTrue(h.canRedo)
        h.record("y", 1, 3)
        assertFalse(h.canRedo)
    }

    @Test fun limitDropsOldestOnly() {
        val h = History<Int>(limit = 3)
        for (i in 0 until 10) h.record("e$i", i, i + 1)
        var n = 0; while (h.undo() != null) n++
        assertEquals(3, n)
    }

    @Test fun faderDragCoalescesIntoOneStep() {
        var now = 0L
        val h = History<Int>(clock = { now })
        h.record("vol", 0, 1, "vol:1"); now += 100
        h.record("vol", 1, 2, "vol:1"); now += 100
        h.record("vol", 2, 3, "vol:1")
        assertEquals(0, h.undo()); assertNull(h.undo())          // one step back to the value before the drag
        assertEquals(3, h.redo())
    }

    @Test fun coalescingDoesNotCrossKeysOrPauses() {
        var now = 0L
        val h = History<Int>(clock = { now })
        h.record("a", 0, 1, "k1"); now += 10
        h.record("b", 1, 2, "k2"); now += 5000
        h.record("b", 2, 3, "k2")
        assertEquals(2, h.undo()); assertEquals(1, h.undo()); assertEquals(0, h.undo())
    }

    @Test fun identicalDocumentIsNotRecorded() {
        val h = History<ProjectDoc>(); val d = ProjectDoc()
        h.record("noop", d, d)
        assertFalse(h.canUndo)
    }
}

class FakeEngine : EngineApi {
    val calls = ArrayList<String>()
    var nextFx = 100
    val effectParams = HashMap<String, List<NativeParam>>()
    var clipResult: (String) -> Int = { 0 }
    override fun resetProject() { calls += "reset" }
    override fun addTrack(id: Int): Boolean { calls += "addTrack $id"; return true }
    override fun removeTrack(id: Int): Boolean { calls += "removeTrack $id"; return true }
    override fun setTrackVolume(id: Int, v: Float) { calls += "vol $id $v" }
    override fun setTrackPan(id: Int, v: Float) { calls += "pan $id $v" }
    override fun setTrackMute(id: Int, m: Boolean) { calls += "mute $id $m" }
    override fun setTrackSolo(id: Int, s: Boolean) { calls += "solo $id $s" }
    override fun setClip(id: Int, trackId: Int, path: String, start: Long, offset: Long, length: Long, gainDb: Float, fadeIn: Long, fadeOut: Long): Int {
        calls += "clip $id t$trackId $path $start $offset $length"; return clipResult(path)
    }
    override fun removeClip(id: Int): Boolean { calls += "rmClip $id"; return true }
    override fun addEffect(trackId: Int, type: String, index: Int): Int { calls += "addFx t$trackId $type @$index"; return nextFx++ }
    override fun removeEffect(nativeId: Int): Boolean { calls += "rmFx $nativeId"; return true }
    override fun setEffectParam(nativeId: Int, index: Int, value: Float): Boolean { calls += "param $nativeId $index $value"; return true }
    override fun setEffectBypass(nativeId: Int, bypass: Boolean): Boolean { calls += "bypass $nativeId $bypass"; return true }
    override fun effectInfo(nativeId: Int) = NativeEffectInfo(nativeId, 0, "x", "x", false,
        listOf("a", "b", "threshold").map { NativeParam(it, it, "", 0f, 1f, 0f, 0f) })
    override fun setMasterVolume(v: Float) { calls += "master $v" }
    override fun setLoop(start: Long, end: Long, enabled: Boolean) { calls += "loop $start $end $enabled" }
    override fun setMetronome(on: Boolean, bpm: Float, beats: Int) { calls += "metro $on $bpm $beats" }
    override fun setCountIn(bars: Int) { calls += "countIn $bars" }
    override fun setTrackSend(id: Int, bus: Int, level: Float) { calls += "send $id $bus $level" }
    override fun setBusLevel(bus: Int, level: Float) { calls += "busLevel $bus $level" }
    override fun setPunch(enabled: Boolean, inFrame: Long, outFrame: Long) { calls += "punch $enabled $inFrame $outFrame" }
}

class EngineSyncTest {
    private fun doc(): ProjectDoc {
        var d = EditOps.addTrack(ProjectDoc(), "T").first
        val t = d.tracks[0].id
        d = EditOps.addClip(d, t, "media/a.wav", "a", 100, 1000, 5000).first
        return d
    }

    @Test fun firstSyncBuildsEverythingWithResolvedPaths() {
        val e = FakeEngine(); val s = EngineSync(e) { "/p/$it" }
        s.sync(doc())
        assertEquals("reset", e.calls.first())
        assertTrue(e.calls.any { it.startsWith("addTrack 1") })
        assertTrue(e.calls.any { it == "clip 2 t1 /p/media/a.wav 100 0 1000" })
    }

    @Test fun incrementalEditsSendOnlyTheDifference() {
        val e = FakeEngine(); val s = EngineSync(e) { it }
        val d = doc(); s.sync(d); e.calls.clear()
        val moved = EditOps.moveClip(d, 2, 5000)
        s.sync(moved)
        assertEquals(listOf("clip 2 t1 media/a.wav 5000 0 1000"), e.calls)
        e.calls.clear()
        val muted = EditOps.updateTrack(moved, 1) { it.copy(mute = true) }
        s.sync(muted)
        assertEquals(listOf("mute 1 true"), e.calls)
        e.calls.clear()
        s.sync(EditOps.deleteClip(muted, 2))
        assertEquals(listOf("rmClip 2"), e.calls)
    }

    @Test fun unchangedDocumentSendsNothing() {
        val e = FakeEngine(); val s = EngineSync(e) { it }
        val d = doc(); s.sync(d); e.calls.clear(); s.sync(d)
        assertTrue(e.calls.isEmpty())
    }

    @Test fun effectLifecycleMapsParameterIdsToIndexes() {
        val e = FakeEngine(); val s = EngineSync(e) { it }
        var d = doc(); s.sync(d); e.calls.clear()
        val (d1, fx) = EditOps.addEffect(d, 1, "compressor")
        d = EditOps.setEffectParam(d1, fx, "threshold", -20f)
        s.sync(d)
        assertTrue(e.calls.contains("addFx t1 compressor @0"))
        assertTrue(e.calls.contains("param 100 2 -20.0"))          // "threshold" is index 2 in the fake
        e.calls.clear()
        d = EditOps.setEffectParam(d, fx, "threshold", -25f); s.sync(d)
        assertEquals(listOf("param 100 2 -25.0"), e.calls)
        e.calls.clear()
        d = EditOps.setEffectBypass(d, fx, true); s.sync(d)
        assertEquals(listOf("bypass 100 true"), e.calls)
        e.calls.clear()
        d = EditOps.removeEffect(d, fx); s.sync(d)
        assertEquals(listOf("rmFx 100"), e.calls)
    }

    @Test fun failedClipIsReportedAndNotRetriedEveryEdit() {
        val e = FakeEngine(); e.clipResult = { 1 }
        val s = EngineSync(e) { it }
        val d = doc()
        val r = s.sync(d)
        assertEquals(mapOf(2 to 1), r.clipErrors)
        e.calls.clear()
        s.sync(EditOps.updateTrack(d, 1) { it.copy(volume = 0.5f) })
        assertFalse(e.calls.any { it.startsWith("clip") })
        assertNotNull(s.errors()[2])
    }

    @Test fun truncatedMediaCountsAsLoaded() {
        val e = FakeEngine(); e.clipResult = { 4 }
        val s = EngineSync(e) { it }
        assertTrue(s.sync(doc()).clipErrors.isEmpty())
    }

    @Test fun forceRebuildResetsTheEngine() {
        val e = FakeEngine(); val s = EngineSync(e) { it }
        val d = doc(); s.sync(d); e.calls.clear()
        s.sync(d, forceRebuild = true)
        assertEquals("reset", e.calls.first())
    }

    @Test fun nativeEffectInfoParsing() {
        val i = NativeEffectInfo.parse("""{"id":3,"trackId":0,"type":"gain","name":"Gain","bypass":false,"params":[{"id":"gain","name":"Gain","unit":"dB","min":-60,"max":24,"def":0,"value":1.5}]}""")!!
        assertEquals("gain", i.params[0].id); assertEquals(1.5f, i.params[0].value, 0f)
        assertNull(NativeEffectInfo.parse("{}")); assertNull(NativeEffectInfo.parse("not json"))
    }
}

class MarkerPunchNormalizeTest {
    @Test fun markersAreSortedNamedAndEditable() {
        var d = ProjectDoc()
        d = EditOps.addMarker(d, 96000, "Chorus").first
        val (d1, id) = EditOps.addMarker(d, 48000)
        d = d1
        assertEquals(listOf(48000L, 96000L), d.markers.map { it.frame })
        assertEquals("Marker 2", d.markers[0].name)
        d = EditOps.moveMarker(d, id, 200000)
        assertEquals(id, d.markers.last().id)               // re-sorted
        d = EditOps.renameMarker(d, id, "  Bridge  ")
        assertEquals("Bridge", d.markers.last().name)
        assertSame(d, EditOps.renameMarker(d, id, "   "))    // blank names rejected
        assertSame(d, EditOps.addMarker(d, -5).first)
        d = EditOps.removeMarker(d, id)
        assertEquals(1, d.markers.size)
        assertSame(d, EditOps.removeMarker(d, 12345))
    }

    @Test fun markerNavigationAdvancesAndDoesNotStick() {
        var d = ProjectDoc()
        for (f in listOf(48000L, 96000L, 192000L)) d = EditOps.addMarker(d, f).first
        assertEquals(48000L, EditOps.nextMarker(d, 0)!!.frame)
        assertEquals(96000L, EditOps.nextMarker(d, 48000)!!.frame)      // standing on a marker: go to the following one
        assertNull(EditOps.nextMarker(d, 192000))
        assertEquals(96000L, EditOps.prevMarker(d, 192000)!!.frame)
        assertEquals(48000L, EditOps.prevMarker(d, 96000 + 100)!!.frame) // just after a marker: skip back past it
        assertNull(EditOps.prevMarker(d, 48000))
    }

    @Test fun punchRangeValidation() {
        val d = ProjectDoc()
        assertSame(d, EditOps.setPunchEnabled(d, true))                  // no range yet
        assertSame(d, EditOps.setPunchRange(d, 5000, 5000))
        assertSame(d, EditOps.setPunchRange(d, 9000, 1000))
        assertSame(d, EditOps.setPunchRange(d, -1, 1000))
        val d2 = EditOps.setPunchEnabled(EditOps.setPunchRange(d, 1000, 9000), true)
        assertTrue(d2.punchEnabled); assertEquals(9000L, d2.punchOut)
        assertSame(d2, EditOps.setPunchRange(d2, 1000, 9000))
    }

    @Test fun punchAndMarkersSyncAndPersist() {
        val e = FakeEngine(); val s = EngineSync(e) { it }
        var d = EditOps.setPunchRange(ProjectDoc(), 1000, 9000)
        s.sync(d); e.calls.clear()
        d = EditOps.setPunchEnabled(d, true)
        s.sync(d)
        assertEquals(listOf("punch true 1000 9000"), e.calls)
        val back = ProjectCodec.decode(ProjectCodec.encode(d))
        assertTrue(back.punchEnabled); assertEquals(1000L, back.punchIn)
        // an enabled flag without a valid range in a file is ignored
        assertFalse(ProjectCodec.decode("""{"punch":{"in":500,"out":100,"enabled":true}}""").punchEnabled)
    }

    @Test fun normalizeGain() {
        assertEquals(6.0206f - 1f, EditOps.normalizeGainDb(0.5f, -1f)!!, 0.001f)
        assertEquals(0f, EditOps.normalizeGainDb(1f, 0f)!!, 0.001f)
        assertEquals(-6.0206f - 0f, EditOps.normalizeGainDb(2f, 0f)!!, 0.001f)
        assertNull(EditOps.normalizeGainDb(0f))
        assertEquals(24f, EditOps.normalizeGainDb(0.0001f)!!, 0f)       // clamped to the clip gain range
    }
}

class ProjectFilesTest {
    private fun tmp(): File = Files.createTempDirectory("wsproj").toFile()

    private fun makeProject(root: File): File {
        val p = File(root, "1"); File(p, "media").mkdirs(); File(p, "recordings").mkdirs()
        File(p, "project.json").writeText("{\"name\":\"A\"}")
        File(p, "project.json.tmp").writeText("partial")
        File(p, "media/a.wav").writeBytes(ByteArray(1000) { it.toByte() })
        File(p, "recordings/t.wav").writeBytes(ByteArray(500))
        File(p, "recordings/t.wav.take.json").writeText("{}")
        return p
    }

    @Test fun duplicateCopiesAudioAndSkipsTemporaryFiles() {
        val root = tmp(); val src = makeProject(root); val dst = File(root, "2")
        ProjectFiles.duplicateProject(src, dst)
        assertEquals("{\"name\":\"A\"}", File(dst, "project.json").readText())
        assertEquals(1000L, File(dst, "media/a.wav").length())
        assertEquals(500L, File(dst, "recordings/t.wav").length())
        assertFalse(File(dst, "project.json.tmp").exists())
        assertFalse(File(dst, "recordings/t.wav.take.json").exists())
        // the copy is independent: editing the duplicate's document does not touch the original
        File(dst, "project.json").writeText("changed")
        assertEquals("{\"name\":\"A\"}", File(src, "project.json").readText())
        root.deleteRecursively()
    }

    @Test fun duplicateRefusesExistingDestinationAndMissingSource() {
        val root = tmp(); val src = makeProject(root)
        try { ProjectFiles.duplicateProject(src, File(root, "1")); fail() } catch (_: java.io.IOException) {}
        try { ProjectFiles.duplicateProject(File(root, "nope"), File(root, "3")); fail() } catch (_: java.io.IOException) {}
        assertTrue(src.exists())          // the original is never touched on failure
        root.deleteRecursively()
    }

    @Test fun deleteRemovesEverythingAndToleratesMissing() {
        val root = tmp(); val src = makeProject(root)
        assertTrue(ProjectFiles.sizeOf(src) > 1500)
        assertTrue(ProjectFiles.deleteProject(src)); assertFalse(src.exists())
        assertTrue(ProjectFiles.deleteProject(src))
        root.deleteRecursively()
    }
}

class AuxBusTest {
    private fun base(): Triple<ProjectDoc, Int, Int> {
        var d = ProjectDoc()
        val (d1, t) = EditOps.addTrack(d); d = d1
        val (d2, b) = EditOps.addBus(d); d = d2
        return Triple(d, t, b)
    }

    @Test fun busesFillTheLowestFreeSlotAndStopAtTheLimit() {
        var d = ProjectDoc()
        for (i in 0 until MAX_BUSES) { val (n, idx) = EditOps.addBus(d); assertEquals(i, idx); d = n }
        assertEquals("Aux 1", d.buses[0].name)
        val (full, idx) = EditOps.addBus(d); assertEquals(-1, idx); assertSame(d, full)
        d = EditOps.removeBus(d, 1)
        assertEquals(1, EditOps.addBus(d, "  ").second)                 // freed slot is reused; blank name falls back to default
    }

    @Test fun sendsAreValidatedAndZeroRemovesThem() {
        val (d, t, b) = base()
        val d1 = EditOps.setTrackSend(d, t, b, 0.5f)
        assertEquals(0.5f, d1.track(t)!!.sends[b]!!, 0f)
        assertSame(d, EditOps.setTrackSend(d, t, 3, 0.5f))                 // bus 3 does not exist
        assertSame(d, EditOps.setTrackSend(d, 999, b, 0.5f))               // unknown track
        assertEquals(2f, EditOps.setTrackSend(d, t, b, 9f).track(t)!!.sends[b]!!, 0f)   // clamped
        assertTrue(EditOps.setTrackSend(d1, t, b, 0f).track(t)!!.sends.isEmpty())
        assertSame(d1, EditOps.setTrackSend(d1, t, b, 0.5f))               // no change -> same instance
    }

    @Test fun removingABusRemovesItsSendsAndEffects() {
        var (d, t, b) = base()
        d = EditOps.setTrackSend(d, t, b, 0.5f)
        d = EditOps.addEffect(d, -(b + 1), "delay").first
        d = EditOps.removeBus(d, b)
        assertTrue(d.buses.isEmpty()); assertTrue(d.track(t)!!.sends.isEmpty())
        assertEquals(-1, EditOps.addEffect(d, -1, "reverb").second)        // bus is gone
    }

    @Test fun busEffectsStartFullyWetAndAreEditable() {
        var (d, _, b) = base()
        val (d1, rid) = EditOps.addEffect(d, -(b + 1), "reverb"); d = d1
        assertEquals(1f, d.bus(b)!!.effects[0].params["wet"]!!, 0f); assertEquals(0f, d.bus(b)!!.effects[0].params["dry"]!!, 0f)
        d = EditOps.setEffectParam(d, rid, "roomsize", 0.9f)
        assertEquals(0.9f, d.bus(b)!!.effects[0].params["roomsize"]!!, 0f)
        d = EditOps.setEffectBypass(d, rid, true)
        assertTrue(d.bus(b)!!.effects[0].bypass)
        val (d2, gid) = EditOps.addEffect(d, -(b + 1), "gain")
        assertTrue(d2.bus(b)!!.effects[1].params.isEmpty())
        d = EditOps.removeEffect(d2, rid)
        assertEquals(listOf(gid), d.bus(b)!!.effects.map { it.id })
        assertEquals(-1, EditOps.addEffect(d, -4, "gain").second)          // bus 3 not present
    }

    @Test fun codecRoundTripsBusesAndIgnoresBadEntries() {
        var (d, t, b) = base()
        d = EditOps.setTrackSend(d, t, b, 0.25f); d = EditOps.setBusLevel(d, b, 1.5f)
        assertEquals(d, ProjectCodec.decode(ProjectCodec.encode(d)))
        val bad = ProjectCodec.decode("""{"buses":[{"index":9,"name":"x"},{"index":1,"name":"ok"},{"index":1,"name":"dup"}],
            "tracks":[{"id":1,"name":"T","sends":{"0":0.5,"7":1,"x":1,"1":-3}}]}""")
        assertEquals(listOf(1), bad.buses.map { it.index })
        assertEquals(mapOf(0 to 0.5f), bad.tracks[0].sends)
    }

    @Test fun syncSendsOnlyWhatChangedAndCleansUpRemovedBuses() {
        val e = FakeEngine(); val s = EngineSync(e) { it }
        var (d, t, b) = base()
        s.sync(d); e.calls.clear()
        d = EditOps.setTrackSend(d, t, b, 0.5f); s.sync(d)
        assertEquals(listOf("send $t $b 0.5"), e.calls); e.calls.clear()
        d = EditOps.setBusLevel(d, b, 0.8f); s.sync(d)
        assertEquals(listOf("busLevel $b 0.8"), e.calls); e.calls.clear()
        d = EditOps.addEffect(d, -(b + 1), "reverb").first; s.sync(d)
        assertTrue(e.calls.first().startsWith("addFx t${-(b + 1)} reverb"))
        e.calls.clear()
        d = EditOps.removeBus(d, b); s.sync(d)
        assertTrue(e.calls.any { it.startsWith("rmFx") }); assertTrue("busLevel $b 1.0" in e.calls); assertTrue("send $t $b 0.0" in e.calls)
        // a full rebuild of an existing project sends the saved sends and levels
        d = EditOps.setTrackSend(EditOps.addBus(d).first, t, 0, 0.3f)
        e.calls.clear(); s.sync(d, forceRebuild = true)
        assertTrue("send $t 0 0.3" in e.calls)
    }
}

class TimelineMathTest {
    @Test fun zoomIsClampedAndNanSafe() {
        assertEquals(TimelineMath.MAX_ZOOM, TimelineMath.clampZoom(1e9f), 0f)
        assertEquals(TimelineMath.MIN_ZOOM, TimelineMath.clampZoom(-5f), 0f)
        assertEquals(100f, TimelineMath.clampZoom(Float.NaN), 0f)
        assertEquals(TimelineMath.MAX_ZOOM, TimelineMath.clampZoom(Float.POSITIVE_INFINITY), 0f)
    }
    @Test fun zoomAroundKeepsAnchorTimeFixed() {
        val total = 48000L * 600
        val (z1, s1) = TimelineMath.zoomAround(100f, 1000f, 300f, 2f, total, 1000f)
        assertEquals(200f, z1, 1e-3f)
        assertEquals((1000f + 300f) / 100f, (s1 + 300f) / z1, 1e-3f)
    }
    @Test fun zoomAroundSurvivesBadInput() {
        val (z, s) = TimelineMath.zoomAround(Float.NaN, Float.NaN, Float.NaN, Float.NaN, -5L, 0f)
        assertTrue(!z.isNaN() && !s.isNaN() && s >= 0f)
        val (z2, _) = TimelineMath.zoomAround(100f, 0f, 10f, 0f, 1000L, 800f)       // factor 0 -> unchanged
        assertEquals(100f, z2, 1e-3f)
        val (z3, s3) = TimelineMath.zoomAround(5000f, 0f, 10f, 100f, 48000L * 10, 800f)
        assertEquals(TimelineMath.MAX_ZOOM, z3, 0f); assertTrue(s3 <= TimelineMath.maxScroll(48000L * 10, z3, 800f))
    }
    @Test fun scrollNeverLeavesContent() {
        assertEquals(0f, TimelineMath.clampScroll(-50f, 48000L * 10, 100f, 800f), 0f)
        assertEquals(1000f - 400f, TimelineMath.clampScroll(1e9f, 48000L * 10, 100f, 800f), 1e-3f)
        assertEquals(0f, TimelineMath.clampScroll(5f, 0L, 100f, 800f), 0f)
        assertEquals(0f, TimelineMath.clampScrollY(500f, 2, 84f, 400f), 0f)
        assertEquals(68f, TimelineMath.clampScrollY(500f, 6, 84f, 436f), 1e-3f)
    }
    @Test fun fitZoomShowsTheWholeProject() {
        val z = TimelineMath.fitZoom(48000L * 120, 900f)
        assertTrue(z * 120f <= 900f && z * 120f > 700f)
        assertTrue(TimelineMath.fitZoom(0L, 900f) in TimelineMath.MIN_ZOOM..TimelineMath.MAX_ZOOM)
    }
    @Test fun visibleBeatsAreBounded() {
        assertTrue(TimelineMath.visibleBeats(0f, 1000f, 0f).isEmpty())
        assertTrue(TimelineMath.visibleBeats(Float.NaN, 1000f, Float.NaN).isEmpty())
        val r = TimelineMath.visibleBeats(0f, 1e12f, 0.001f)
        assertTrue(r.last - r.first <= 4000)
        assertEquals(2, TimelineMath.visibleBeats(100f, 100f, 100f).first + 1)
    }
    @Test fun rulerStepsAndLabels() {
        assertEquals(1f, TimelineMath.rulerStepSeconds(100f), 0f)
        assertEquals(60f, TimelineMath.rulerStepSeconds(2f), 0f)
        assertEquals("1:05", TimelineMath.timeLabel(65f)); assertEquals("0:00", TimelineMath.timeLabel(Float.NaN))
        assertEquals("0:00.5", TimelineMath.timeLabel(0.5f))
    }
    @Test fun frameAtIsTotal() {
        assertEquals(48000L, TimelineMath.frameAt(100f, 0f, 100f))
        assertEquals(0L, TimelineMath.frameAt(-500f, 0f, 100f)); assertEquals(0L, TimelineMath.frameAt(Float.NaN, Float.NaN, Float.NaN))
        val (a, b) = TimelineMath.clipSpan(-1e9f, 1e9f, 800f); assertEquals(-24f, a, 0f); assertEquals(824f, b, 0f)
    }
}

class EffectPresetTest {
    @Test fun presetsHaveUniqueIdsAndKnownShape() {
        assertEquals(EffectPresets.all.size, EffectPresets.all.map { it.id }.toSet().size)
        for (p in EffectPresets.all) { assertTrue(p.name.isNotBlank()); assertTrue(p.chain.isNotEmpty()) }
        assertNotNull(EffectPresets.byId("voice_clean")); assertNull(EffectPresets.byId("nope"))
    }
    @Test fun applyAppendsOrReplaces() {
        var d = ProjectDoc()
        val (d1, t) = EditOps.addTrack(d); d = d1
        d = EditOps.addEffect(d, t, "gain").first
        val clean = EffectPresets.byId("voice_clean")!!
        val a = EditOps.applyPreset(d, t, clean)
        assertEquals(1 + clean.chain.size, a.track(t)!!.effects.size)
        assertEquals(90f, a.track(t)!!.effects[1].params["freq"]!!, 0f)
        val r = EditOps.applyPreset(d, t, clean, replace = true)
        assertEquals(clean.chain.map { it.type }, r.track(t)!!.effects.map { it.type })
        assertSame(d, EditOps.applyPreset(d, 999, clean))                        // unknown track: unchanged
        val m = EditOps.applyPreset(d, 0, EffectPresets.byId("hard_tune")!!)
        assertEquals("autotune", m.masterEffects.last().type)
    }
    @Test fun presetsOnBusesAreForcedFullyWet() {
        var d = ProjectDoc(); d = EditOps.addBus(d).first
        val out = EditOps.applyPreset(d, -1, EffectPresets.byId("wide_double")!!)
        assertEquals(1f, out.bus(0)!!.effects[0].params["mix"]!!, 0f)
        assertEquals(2, out.bus(0)!!.effects.size)
    }
    @Test fun nativeParamOptionsParse() {
        val i = NativeEffectInfo.parse("""{"id":3,"trackId":0,"type":"autotune","name":"Auto-Tune","bypass":false,"params":[{"id":"scale","name":"Scale","unit":"","min":0,"max":7,"def":0,"value":1,"options":["Chromatic","Major"]},{"id":"speed","name":"S","unit":"ms","min":0,"max":400,"def":20,"value":20}]}""")!!
        assertEquals(listOf("Chromatic", "Major"), i.params[0].options); assertTrue(i.params[1].options.isEmpty())
    }
}


class AppSettingsDataTest {
    @Test fun defaultsAreValidAndStable() {
        val d = AppSettingsData()
        assertEquals(d, d.sanitized())
        assertEquals(24, d.recordBitDepth); assertTrue(d.removeDc); assertEquals(0, d.inputLowCutHz)
    }
    @Test fun sanitizeClampsEverything() {
        val s = AppSettingsData(accentIndex = 99, recordBitDepth = 20, inputGainDb = 80f, inputLowCutHz = 95,
            exportSampleRate = 12345, exportBitDepth = 8).sanitized()
        assertEquals(AppSettingsData.ACCENTS.size - 1, s.accentIndex)
        assertEquals(24, s.recordBitDepth); assertEquals(24f, s.inputGainDb, 0f)
        assertEquals(80, s.inputLowCutHz); assertEquals(48000, s.exportSampleRate); assertEquals(24, s.exportBitDepth)
        assertEquals(0f, AppSettingsData(inputGainDb = Float.NaN).sanitized().inputGainDb, 0f)
        assertEquals(-3.5f, AppSettingsData(inputGainDb = -3.4f).sanitized().inputGainDb, 0f)
        assertEquals(0, AppSettingsData(accentIndex = -4).sanitized().accentIndex)
    }
    @Test fun flacNeverKeeps32Bit() {
        assertEquals(24, AppSettingsData(exportFlac = true, exportBitDepth = 32).sanitized().exportBitDepth)
        assertEquals(32, AppSettingsData(exportFlac = false, exportBitDepth = 32).sanitized().exportBitDepth)
    }
    @Test fun themeParse() {
        assertEquals(ThemeMode.DARK, ThemeMode.parse("DARK")); assertEquals(ThemeMode.SYSTEM, ThemeMode.parse("nonsense")); assertEquals(ThemeMode.SYSTEM, ThemeMode.parse(null))
        assertTrue(AppSettingsData().accent.argb != 0L)
    }
}

class StudioTest {
    private fun rms(a: FloatArray, from: Int, to: Int): Double { var s = 0.0; for (i in from until minOf(to, a.size)) s += a[i].toDouble() * a[i]; return Math.sqrt(s / maxOf(1, to - from)) }
    private fun zeroCrossHz(a: FloatArray, from: Int, to: Int, rate: Int): Double {
        var c = 0; for (i in from + 1 until to) if ((a[i - 1] < 0) != (a[i] < 0)) c++
        return c / 2.0 / ((to - from).toDouble() / rate)
    }

    private fun acPitch(a: FloatArray, from: Int, to: Int, rate: Int, minHz: Double, maxHz: Double): Double {
        var best = 0.0; var bestLag = 0
        for (lag in (rate / maxHz).toInt()..(rate / minHz).toInt()) {
            var c = 0.0; var e = 0.0
            for (i in from until to) { c += a[i].toDouble() * a[i + lag]; e += a[i + lag].toDouble() * a[i + lag] }
            val n = if (e > 0) c / Math.sqrt(e) else 0.0
            if (n > best) { best = n; bestLag = lag }
        }
        return rate.toDouble() / bestLag
    }

    @Test fun beatOpsAndNormalization() {
        var b = BeatDoc()
        assertEquals(8, b.rows.size)
        b = StudioOps.cycleStep(b, DrumVoice.KICK, 0)
        assertEquals(2, b.rows[0].at(0))
        b = StudioOps.cycleStep(b, DrumVoice.KICK, 0); assertEquals(3, b.rows[0].at(0))
        b = StudioOps.cycleStep(b, DrumVoice.KICK, 0); assertEquals(1, b.rows[0].at(0))
        b = StudioOps.cycleStep(b, DrumVoice.KICK, 0); assertEquals(0, b.rows[0].at(0))
        assertEquals(b, StudioOps.cycleStep(b, DrumVoice.KICK, 999))                  // out of range: no change
        val g = StudioOps.setBars(StudioOps.demoBeat(1), 4)
        assertEquals(64, g.rows[0].steps.length); assertEquals(StudioOps.hitCount(StudioOps.demoBeat(1)), StudioOps.hitCount(g))
        assertEquals(16, StudioOps.setBars(g, 1).rows[0].steps.length)
        assertEquals(MAX_BAR_COUNT, StudioOps.setBars(g, 99).bars)
        assertEquals(0, StudioOps.hitCount(StudioOps.clearBeat(g)))
        val damaged = StudioOps.normalizeBeat(BeatDoc(bars = 1, swing = Float.NaN, rows = listOf(DrumRowDoc("kick", "9x3"), DrumRowDoc("nonsense", "3333"))))
        assertEquals(8, damaged.rows.size); assertEquals(16, damaged.rows[0].steps.length); assertEquals(0f, damaged.swing, 0f)
        assertEquals("3", damaged.rows[0].steps.substring(0, 1).let { if (damaged.rows[0].steps.contains('3')) "3" else it })
    }

    @Test fun rollOps() {
        var r = RollDoc(bars = 1)
        r = StudioOps.addNote(r, 60, 0, 4)
        r = StudioOps.addNote(r, 60, 2, 4)                           // overlaps on the same pitch: replaces
        assertEquals(1, r.notes.size); assertEquals(2, r.notes[0].step)
        r = StudioOps.addNote(r, 64, 15, 40)                          // length clamped to the end of the roll
        assertEquals(1, r.notes.first { it.pitch == 64 }.length)
        r = StudioOps.addNote(r, 5, 0, 1); r = StudioOps.addNote(r, 60, 99, 1)   // out of range: ignored
        assertEquals(2, r.notes.size)
        assertNotNull(StudioOps.noteAt(r, 60, 5)); assertNull(StudioOps.noteAt(r, 60, 6))
        r = StudioOps.removeNoteAt(r, 60, 3); assertEquals(1, r.notes.size)
        assertEquals(66, StudioOps.transpose(r, 2).notes[0].pitch)
        assertEquals(0, StudioOps.transpose(r, 200).notes.size)       // pushed out of range
        assertEquals(0, StudioOps.setRollBars(StudioOps.demoRoll(), 1).notes.count { it.step >= 16 })
        assertEquals("C4", StudioOps.pitchName(60)); assertEquals("A#2", StudioOps.pitchName(46)); assertTrue(StudioOps.isBlackKey(61)); assertFalse(StudioOps.isBlackKey(60))
    }

    @Test fun studioSurvivesSaveLoad() {
        val d = ProjectDoc(studio = StudioDoc(StudioOps.demoBeat(2).copy(swing = 0.2f), StudioOps.demoRoll().copy(instrument = "bass")))
        val back = ProjectCodec.decode(ProjectCodec.encode(d))
        assertEquals(d.studio, back.studio)
        // a project written before the studio existed opens with an empty studio
        val old = Json.write(ProjectCodec.toMap(ProjectDoc()).filterKeys { it != "studio" })
        assertEquals(StudioDoc(), ProjectCodec.decode(old).studio)
        // garbage inside the studio object must not break the project
        val m = LinkedHashMap(ProjectCodec.toMap(ProjectDoc())); m["studio"] = "not an object"
        assertEquals(StudioDoc(), ProjectCodec.decode(Json.write(m)).studio)
    }

    @Test fun drumHitsAreFiniteBoundedAndDistinct() {
        val seen = HashSet<Int>()
        for (v in DrumVoice.values()) {
            val h = Synth.drumHit(v)
            assertTrue(v.id, h.size > 1000)
            var pk = 0f; for (s in h) { assertTrue(s.isFinite()); pk = maxOf(pk, Math.abs(s)) }
            assertEquals(v.id, 0.9f, pk, 0.001f)
            assertEquals("deterministic", h.toList(), Synth.drumHit(v).toList())
            seen.add(h.contentHashCode())
        }
        assertEquals(DrumVoice.values().size, seen.size)
        // the kick is low (below 200 Hz in its body), the closed hat is bright
        val kick = Synth.drumHit(DrumVoice.KICK)
        assertTrue(zeroCrossHz(kick, 4800, 14400, 48000) < 200)
        val hat = Synth.drumHit(DrumVoice.HAT)
        assertTrue(zeroCrossHz(hat, 100, 3000, 48000) > 3000)
    }

    @Test fun beatTimingLengthAndSilence() {
        val rate = 48000
        val empty = Synth.renderBeat(BeatDoc(), 120f)
        assertEquals(rate * 2, empty.frames)                          // 1 bar at 120 bpm = 2 s exactly
        assertEquals(0f, empty.peak(), 0f)
        var b = StudioOps.cycleStep(BeatDoc(), DrumVoice.KICK, 4)     // step 4 = beat 2 = 0.5 s
        val r = Synth.renderBeat(b, 120f)
        assertEquals(0.0, rms(r.left, 0, rate / 2 - 8), 0.0)          // nothing before the hit
        assertTrue(rms(r.left, rate / 2, rate / 2 + 2400) > 0.05)
        assertTrue(r.peak() <= 1f)
        assertEquals(r.left.toList(), Synth.renderBeat(b, 120f).left.toList())
        // tempo changes the length; 4 bars at 90 bpm = 10.666 s
        assertEquals(Math.round(4 * 4 * 60.0 / 90 * rate).toInt(), Synth.renderBeat(StudioOps.setBars(BeatDoc(), 4), 90f).frames)
        // swing moves odd steps later and never even ones
        val hatAt1 = StudioOps.cycleStep(BeatDoc(), DrumVoice.HAT, 1)
        val straight = Synth.renderBeat(hatAt1, 120f); val swung = Synth.renderBeat(StudioOps.setSwing(hatAt1, 0.5f), 120f)
        fun firstNonZero(a: FloatArray): Int { for (i in a.indices) if (Math.abs(a[i]) > 1e-4f) return i; return -1 }
        assertTrue(firstNonZero(swung.left) > firstNonZero(straight.left) + 1000)
        // mute silences a lane; a loop wraps the tail of the last hit to the start
        assertEquals(0f, Synth.renderBeat(StudioOps.setRow(b, DrumVoice.KICK, mute = true), 120f).peak(), 0f)
        val last = StudioOps.cycleStep(BeatDoc(), DrumVoice.OPEN_HAT, 15)
        val looped = Synth.renderBeat(last, 120f, wrap = true); val oneShot = Synth.renderBeat(last, 120f, wrap = false)
        assertTrue(rms(looped.left, 0, 2000) > 0.01); assertEquals(0.0, rms(oneShot.left, 0, 2000), 0.0)
        assertTrue(oneShot.frames > looped.frames)
    }

    @Test fun dense_beatStaysBounded() {
        var b = BeatDoc(bars = 2)
        for (v in DrumVoice.values()) for (s in 0 until 32) b = StudioOps.cycleStep(StudioOps.cycleStep(b, v, s), v, s)   // all accent
        val r = Synth.renderBeat(b, 200f)
        assertTrue(r.peak() <= 1.0f); for (x in r.left) assertTrue(x.isFinite())
        assertTrue(r.peak() > 0.5f)
    }

    @Test fun notesHaveTheRightPitchAndEnvelope() {
        val rate = 48000
        var roll = RollDoc(bars = 1, instrument = "sine")
        roll = StudioOps.addNote(roll, 69, 0, 8)                      // A4 for half a bar (1 s at 120 bpm)
        val r = Synth.renderRoll(roll, 120f)
        assertEquals(440.0, zeroCrossHz(r.left, 4800, 40000, rate), 4.0)
        assertTrue(rms(r.left, 12000, 24000) > 0.1)
        // releases to silence, finite, and the note ends where the note-off plus release says
        assertEquals(0.0, rms(r.left, r.frames - 32, r.frames), 1e-3)
        for (x in r.left) assertTrue(x.isFinite())
        // an octave up is twice the frequency, in every instrument without a hard cutoff problem
        for (inst in Instruments.all) {
            val one = Synth.renderPreviewNote(inst.id, 57, 0.5f)
            val two = Synth.renderPreviewNote(inst.id, 69, 0.5f)
            assertTrue(inst.id, one.peak() > 0.05f && one.peak() <= 1f)
            assertEquals(inst.id, 220.0, acPitch(one.left, 3000, 6000, rate, 150.0, 300.0), 4.0)
            assertEquals(inst.id, 440.0, acPitch(two.left, 3000, 6000, rate, 300.0, 600.0), 8.0)
        }
        // louder velocity is louder
        val soft = Synth.renderRoll(RollDoc(1, "sine", listOf(RollNote(60, 0, 4, 0.2f))), 120f)
        val loud = Synth.renderRoll(RollDoc(1, "sine", listOf(RollNote(60, 0, 4, 1.0f))), 120f)
        assertTrue(loud.peak() > soft.peak() * 3)
        assertEquals(0f, Synth.renderRoll(RollDoc(1, "keys", emptyList()), 120f).peak(), 0f)
    }

    @Test fun chordsAndLongRollsStayBounded() {
        var roll = RollDoc(bars = 8, instrument = "pad")
        for (s in 0 until 128 step 8) for (p in listOf(48, 52, 55, 60, 64, 67, 72)) roll = StudioOps.addNote(roll, p, s, 8)
        val r = Synth.renderRoll(roll, 140f)
        assertTrue(r.peak() <= 1f); assertTrue(r.peak() > 0.2f)
        for (x in r.right) assertTrue(x.isFinite())
    }

    @Test fun wavWriterProducesAReadableFile() {
        val dir = Files.createTempDirectory("wavout").toFile()
        val buf = Synth.renderBeat(StudioOps.demoBeat(1), 120f)
        for (bits in intArrayOf(16, 24)) {
            val f = File(dir, "t$bits.wav"); WavOut.write(f, buf, 48000, bits)
            val bytes = f.readBytes()
            assertEquals("RIFF", String(bytes, 0, 4)); assertEquals("data", String(bytes, 36, 4))
            val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            assertEquals(bits, bb.getShort(34).toInt()); assertEquals(48000, bb.getInt(24))
            assertEquals(buf.frames * 2 * bits / 8, bb.getInt(40)); assertEquals(44 + buf.frames * 2 * bits / 8, bytes.size)
            assertFalse(File(dir, "t$bits.wav.part").exists())
        }
        // 24-bit round trip is accurate to 1 LSB-ish
        val b = java.nio.ByteBuffer.wrap(File(dir, "t24.wav").readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var maxErr = 0.0
        for (i in 0 until 5000) {
            val o = 44 + i * 6
            val v = (b.get(o).toInt() and 0xFF) or ((b.get(o + 1).toInt() and 0xFF) shl 8) or (b.get(o + 2).toInt() shl 16)
            maxErr = maxOf(maxErr, Math.abs(v / 8388607.0 - buf.left[i]))
        }
        assertTrue(maxErr < 2e-7)
        dir.deleteRecursively()
    }
}
