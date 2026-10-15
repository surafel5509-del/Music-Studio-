package com.wavestudio.app

import com.wavestudio.app.domain.dbToLinear
import com.wavestudio.app.domain.linearToDb
import org.junit.Assert
import org.junit.Test

class AudioMathTest {
    @Test
    fun dbLinearRoundTrip() {
        val db = -6.0f
        val lin = dbToLinear(db)
        val db2 = linearToDb(lin)
        assertEquals(db, db2, 0.01f)
    }

    @Test
    fun unityGain() {
        assertEquals(1f, dbToLinear(0f), 0.001f)
        assertEquals(0f, linearToDb(1f), 0.001f)
    }

    @Test
    fun negativeInfinity() {
        assertEquals(Float.NEGATIVE_INFINITY, linearToDb(0f))
    }
}
