package com.wavestudio.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wavestudio.app.data.repository.ProjectRepository
import com.wavestudio.app.domain.Project
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.wavestudio.app.session.ProjectLibrary
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: ProjectRepository,
    private val library: ProjectLibrary
) : ViewModel() {

    val recentProjects: StateFlow<List<Project>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createProject(name: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repo.create(Project(name = name, sampleRate = 48000f, bitDepth = 24, channels = 2))
            onCreated(id)
        }
    }

    fun deleteProject(id: Long) { viewModelScope.launch { library.delete(id) } }
    fun renameProject(id: Long, name: String) { viewModelScope.launch { library.rename(id, name) } }
    fun duplicateProject(id: Long) { viewModelScope.launch { library.duplicate(id) } }
}
