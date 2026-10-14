package com.wavestudio.app.data.repository

import com.wavestudio.app.domain.Project
import kotlinx.coroutines.flow.Flow

interface ProjectRepository {
    fun observeAll(): Flow<List<Project>>
    suspend fun getAll(): List<Project>
    suspend fun findById(id: Long): Project?
    suspend fun create(project: Project): Long
    suspend fun update(project: Project)
    suspend fun delete(id: Long)
}
