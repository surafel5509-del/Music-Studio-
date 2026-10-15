package com.wavestudio.app.editor

import com.wavestudio.app.domain.AudioClip
import com.wavestudio.app.domain.Automation
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Clipboard @Inject constructor() {
    var clips: List<AudioClip> = emptyList()
        private set
    var automation: List<Automation> = emptyList()
        private set

    fun copyClips(c: List<AudioClip>) { clips = c.map { it.copy() } }
    fun copyAutomation(a: List<Automation>) { automation = a.map { it.copy() } }
    fun clear() { clips = emptyList(); automation = emptyList() }
}
