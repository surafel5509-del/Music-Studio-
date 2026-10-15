package com.wavestudio.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineSmokeTest {
    @Test
    fun appContextIsCorrect() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("com.wavestudio.app.debug", ctx.packageName)
    }

    @Test
    fun nativeLibraryLoads() {
        // Ensures libwavestudio.so loads correctly
        System.loadLibrary("wavestudio")
    }
}
