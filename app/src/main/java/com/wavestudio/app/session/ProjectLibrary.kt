package com.wavestudio.app.session

import android.content.Context
import com.wavestudio.app.data.repository.ProjectRepository
import com.wavestudio.app.project.ProjectFiles
import com.wavestudio.app.project.ProjectStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whole-project operations (rename / duplicate / delete) that keep the Room index and the project folder in step.
 * The open project, if it is the one affected, is saved or closed first so the session never writes into a folder that was removed.
 */
@Singleton
class ProjectLibrary @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val repo: ProjectRepository,
    private val session: ProjectSession
) {
    private fun dir(id: Long) = File(ctx.filesDir, "projects/$id")

    /** Deletes the project folder (audio included) and then its index row. Returns false if files could not be removed (the row is kept). */
    suspend fun delete(id: Long): Boolean {
        session.closeIfOpen(id)
        val ok = withContext(Dispatchers.IO) { ProjectFiles.deleteProject(dir(id)) }
        if (ok) repo.delete(id)
        return ok
    }

    suspend fun rename(id: Long, newName: String) {
        val name = newName.trim().take(60)
        if (name.isEmpty()) return
        if (session.isOpen(id)) {
            session.edit("Rename project") { it.copy(name = name) }
            withContext(Dispatchers.IO) { session.saveBlocking() }
            return
        }
        val row = repo.findById(id) ?: return
        withContext(Dispatchers.IO) {
            val store = ProjectStore(dir(id))
            if (store.projectFile.exists() || store.backupFile.exists()) {
                try { store.save(store.load().doc.copy(name = name, modified = System.currentTimeMillis())) } catch (_: Exception) { /* keep the row name only */ }
            }
        }
        repo.update(row.copy(name = name, modified = System.currentTimeMillis()))
    }

    /** Creates an independent copy named "<name> copy". Returns the new id, or null on failure (nothing is left behind). */
    suspend fun duplicate(id: Long): Long? {
        val row = repo.findById(id) ?: return null
        if (session.isOpen(id)) withContext(Dispatchers.IO) { session.saveBlocking() }
        val now = System.currentTimeMillis()
        val newId = repo.create(row.copy(id = 0, name = (row.name + " copy").take(60), created = now, modified = now))
        val ok = withContext(Dispatchers.IO) {
            try {
                val src = dir(id)
                if (src.isDirectory) {
                    ProjectFiles.duplicateProject(src, dir(newId))
                    val st = ProjectStore(dir(newId))
                    st.save(st.load().doc.copy(name = (row.name + " copy").take(60), created = now, modified = now))
                }
                true
            } catch (_: Exception) { false }
        }
        if (!ok) { repo.delete(newId); return null }
        return newId
    }
}
