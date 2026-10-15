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
import javax.inject.Inject

@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val repo: ProjectRepository
) : ViewModel() {
    val projects: StateFlow<List<Project>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createProject(name: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repo.create(Project(name = name))
            onCreated(id)
        }
    }

    fun deleteProject(id: Long) {
        viewModelScope.launch { repo.delete(id) }
    }
}
