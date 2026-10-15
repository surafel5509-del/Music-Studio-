package com.wavestudio.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val sampleRate: Float = 48000f,
    val bitDepth: Int = 24,
    val channels: Int = 2,
    val bpm: Float = 120f,
    val timeSignatureNum: Int = 4,
    val timeSignatureDen: Int = 4,
    val created: Long = System.currentTimeMillis(),
    val modified: Long = System.currentTimeMillis(),
    val filePath: String = "",
    val durationFrames: Long = 0L,
    val thumbnailPath: String? = null,
    val lastOpened: Long = System.currentTimeMillis()
)
