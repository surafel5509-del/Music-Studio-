package com.wavestudio.app.ui.filebrowser

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

data class FileEntry(val name: String, val path: String, val isDirectory: Boolean, val size: Long = 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var currentDir by remember { mutableStateOf<File>(Environment.getExternalStorageDirectory() ?: Environment.getDataDirectory()) }
    val entries = remember(currentDir) {
        val list = currentDir.listFiles()?.filter { f ->
            f.isDirectory || f.extension.lowercase() in listOf("wav","flac","mp3","aac","ogg","m4a","aiff","wsproj","mid","midi")
        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        list.map { FileEntry(it.name, it.absolutePath, it.isDirectory, it.length()) }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text("File Browser"); Text(currentDir.absolutePath, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) } },
            navigationIcon = { IconButton(onClick = { if (currentDir.parentFile != null) currentDir = currentDir.parentFile!! else onBack() }) {
                Icon(Icons.Default.ArrowBack, null)
            }}
        )
    }) { pad ->
        LazyColumn(modifier = Modifier.padding(pad).fillMaxSize()) {
            items(entries) { e ->
                ListItem(
                    headlineContent = { Text(e.name, fontSize = 13.sp) },
                    supportingContent = {
                        if (e.isDirectory) Text("Folder", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                        else Text("${"%.1f".format(e.size/1024f/1024f)} MB", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    },
                    leadingContent = {
                        Icon(if (e.isDirectory) Icons.Default.Folder else Icons.Default.Audiotrack, null,
                            tint = if (e.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                    },
                    modifier = Modifier.clickable {
                        val f = File(e.path)
                        if (f.isDirectory) currentDir = f
                    }
                )
                Divider()
            }
        }
    }
}
