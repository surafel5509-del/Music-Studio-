package com.wavestudio.app.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wavestudio.app.data.repository.ProjectRepository
import com.wavestudio.app.domain.Project
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.wavestudio.app.session.ProjectLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val repo: ProjectRepository,
    private val library: ProjectLibrary
) : ViewModel() {
    val projects: StateFlow<List<Project>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createProject(name: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repo.create(Project(name = name))
            onCreated(id)
        }
    }

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    fun deleteProject(id: Long) {
        viewModelScope.launch { if (!library.delete(id)) _message.value = "Could not delete the project files" }
    }
    fun renameProject(id: Long, name: String) { viewModelScope.launch { library.rename(id, name) } }
    fun duplicateProject(id: Long) {
        viewModelScope.launch { if (library.duplicate(id) == null) _message.value = "Could not duplicate the project" }
    }
}
