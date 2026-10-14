package com.wavestudio.app.ui.projects

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.wavestudio.app.ui.home.ProjectCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    onOpenProject: (Long) -> Unit,
    onBack: () -> Unit,
    vm: ProjectsViewModel = hiltViewModel()
) {
    val projects by vm.projects.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Projects") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = {}) { Icon(Icons.Default.Search, "Search") }
                    IconButton(onClick = {}) { Icon(Icons.Default.Sort, "Sort") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.createProject("New Project", onOpenProject) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New Project") }
            )
        }
    ) { pad ->
        if (projects.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.LibraryMusic, null, modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text("No projects yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Tap + to start a new music project.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(180.dp),
                modifier = Modifier.padding(pad).fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(projects) { p -> ProjectCard(p, onClick = { onOpenProject(p.id) }) }
            }
        }
    }
}
