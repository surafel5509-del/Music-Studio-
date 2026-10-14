package com.wavestudio.app.ui.home

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.wavestudio.app.domain.Project
import com.wavestudio.app.ui.common.SectionCard
import com.wavestudio.app.ui.common.ToolbarButton
import com.wavestudio.app.ui.theme.WaveStudioTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenProjects: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlugins: () -> Unit,
    onNewProject: () -> Unit,
    onOpenProject: (Long) -> Unit,
    onBrowseFiles: () -> Unit,
    vm: HomeViewModel = hiltViewModel()
) {
    val recent by vm.recentProjects.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val columns = when {
        configuration.screenWidthDp >= 840 -> 3
        configuration.screenWidthDp >= 600 -> 2
        else -> 1
    }

    var showNewDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GraphicEq, "WaveStudio", tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("WaveStudio", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenPlugins) { Icon(Icons.Default.Extension, "Plugins") }
                    IconButton(onClick = onBrowseFiles) { Icon(Icons.Default.Folder, "Files") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewDialog = true },
                icon = { Icon(Icons.Default.Add, "New") },
                text = { Text("New Project") },
                containerColor = MaterialTheme.colorScheme.primary
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isLandscape) {
                Row(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        QuickActionsCard(onNew = { showNewDialog = true }, onOpen = onOpenProjects, onFiles = onBrowseFiles)
                        Spacer(Modifier.height(16.dp))
                        ToolsCard(onPlugins = onOpenPlugins, onSettings = onOpenSettings)
                    }
                    Column(modifier = Modifier.weight(2f).fillMaxHeight()) {
                        RecentProjects(recent, onOpenProject, columns = 2)
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    QuickActionsCard(onNew = { showNewDialog = true }, onOpen = onOpenProjects, onFiles = onBrowseFiles)
                    ToolsCard(onPlugins = onOpenPlugins, onSettings = onOpenSettings)
                    RecentProjects(recent, onOpenProject, columns = columns)
                }
            }
        }
    }

    if (showNewDialog) {
        NewProjectDialog(
            onDismiss = { showNewDialog = false },
            onCreate = { name -> vm.createProject(name) { id -> showNewDialog = false; onOpenProject(id) } }
        )
    }
}

@Composable
fun QuickActionsCard(onNew: () -> Unit, onOpen: () -> Unit, onFiles: () -> Unit) {
    SectionCard(title = "Quick Start") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ToolbarButton(Icons.Default.AddCircle, "New", onClick = onNew, modifier = Modifier.weight(1f))
            ToolbarButton(Icons.Default.FolderOpen, "Open", onClick = onOpen, modifier = Modifier.weight(1f))
            ToolbarButton(Icons.Default.Audiotrack, "Import", onClick = onFiles, modifier = Modifier.weight(1f))
            ToolbarButton(Icons.Default.Mic, "Record", onClick = onNew, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun ToolsCard(onPlugins: () -> Unit, onSettings: () -> Unit) {
    SectionCard(title = "Tools") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ToolbarButton(Icons.Default.Tune, "Plugins", onClick = onPlugins, modifier = Modifier.weight(1f))
            ToolbarButton(Icons.Default.Equalizer, "Mixer", onClick = onPlugins, modifier = Modifier.weight(1f))
            ToolbarButton(Icons.Default.GraphicEq, "Analyzer", onClick = onPlugins, modifier = Modifier.weight(1f))
            ToolbarButton(Icons.Default.Settings, "Settings", onClick = onSettings, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun RecentProjects(projects: List<Project>, onOpen: (Long) -> Unit, columns: Int) {
    SectionCard(title = "Recent Projects") {
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.LibraryMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No projects yet. Tap + to create one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)
            ) {
                items(projects) { p ->
                    ProjectCard(p, onClick = { onOpen(p.id) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectCard(p: Project, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().height(80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                WaveStudioTheme.colors.trackA.copy(alpha = 0.6f),
                                WaveStudioTheme.colors.trackC.copy(alpha = 0.4f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.LibraryMusic, null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${(p.sampleRate/1000).toInt()} kHz / ${p.bitDepth}-bit / ${p.channels} ch",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("BPM ${p.bpm.toInt()} · ${p.timeSignatureNum}/${p.timeSignatureDen}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var sr by remember { mutableStateOf(48000f) }
    var bd by remember { mutableStateOf(24) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Project") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Project name") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Text("Sample Rate", style = MaterialTheme.typography.labelMedium)
                Row {
                    listOf(44100f, 48000f, 96000f, 192000f).forEach { s ->
                        FilterChip(
                            selected = sr == s, onClick = { sr = s },
                            label = { Text("${(s/1000).toInt()}k") },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Bit Depth", style = MaterialTheme.typography.labelMedium)
                Row {
                    listOf(16, 24, 32).forEach { b ->
                        FilterChip(
                            selected = bd == b, onClick = { bd = b },
                            label = { Text("${b}bit") },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name.ifBlank { "Untitled Project" }) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
