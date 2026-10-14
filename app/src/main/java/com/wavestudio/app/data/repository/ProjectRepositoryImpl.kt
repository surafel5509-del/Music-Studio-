package com.wavestudio.app.data.repository

import com.wavestudio.app.data.db.dao.ProjectDao
import com.wavestudio.app.data.db.entity.ProjectEntity
import com.wavestudio.app.domain.Project
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepositoryImpl @Inject constructor(
    private val dao: ProjectDao
) : ProjectRepository {

    override fun observeAll(): Flow<List<Project>> = dao.observeAll().map { list ->
        list.map { it.toDomain() }
    }

    override suspend fun getAll(): List<Project> = dao.getAll().map { it.toDomain() }

    override suspend fun findById(id: Long): Project? = dao.findById(id)?.toDomain()

    override suspend fun create(project: Project): Long = dao.insert(project.toEntity())

    override suspend fun update(project: Project) {
        dao.update(project.toEntity().copy(lastOpened = System.currentTimeMillis()))
    }

    override suspend fun delete(id: Long) = dao.deleteById(id)

    private fun ProjectEntity.toDomain() = Project(
        id = id, name = name, sampleRate = sampleRate, bitDepth = bitDepth,
        channels = channels, bpm = bpm, timeSignatureNum = timeSignatureNum,
        timeSignatureDen = timeSignatureDen, created = created, modified = modified,
        filePath = filePath, durationFrames = durationFrames
    )

    private fun Project.toEntity() = ProjectEntity(
        id = id, name = name, sampleRate = sampleRate, bitDepth = bitDepth,
        channels = channels, bpm = bpm, timeSignatureNum = timeSignatureNum,
        timeSignatureDen = timeSignatureDen, created = created, modified = System.currentTimeMillis(),
        filePath = filePath, durationFrames = durationFrames, lastOpened = System.currentTimeMillis()
    )
}
