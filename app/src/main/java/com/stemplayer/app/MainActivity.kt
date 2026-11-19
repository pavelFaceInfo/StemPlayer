package com.stemplayer.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Needed to show the playback notification on Android 13+.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App(vm: AppViewModel = viewModel()) {
    val current by vm.current.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    val song = current
    if (song == null) SongListScreen(vm) else PlayerScreen(vm, song)

    error?.let {
        AlertDialog(
            onDismissRequest = vm::clearError,
            confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } },
            title = { Text("Something went wrong") },
            text = { Text(it) },
        )
    }
}

// ---------------------------------------------------------------- Song list

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongListScreen(vm: AppViewModel) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    var picked by remember { mutableStateOf<List<Uri>?>(null) }
    var renaming by remember { mutableStateOf<Song?>(null) }
    var deleting by remember { mutableStateOf<Song?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) picked = uris
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Stem Player") }) },
        floatingActionButton = {
            if (importing == null) {
                ExtendedFloatingActionButton(
                    onClick = { picker.launch(IMPORT_MIME_TYPES) },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("New song") },
                )
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            importing?.let { imp ->
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Importing \"${imp.songName}\"… (${imp.done}/${imp.total})")
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { if (imp.total == 0) 0f else imp.done.toFloat() / imp.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (songs.isEmpty() && importing == null) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No songs yet.\nTap \"New song\" and select all the stem files of a piece " +
                            "(e.g. the Soprano, Alto, Tenor and Bass exports from MuseScore), " +
                            "optionally together with its score (.mxl).",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                items(songs, key = { it.id }) { song ->
                    SongRow(
                        song,
                        onClick = { vm.open(song) },
                        onRename = { renaming = song },
                        onDelete = { deleting = song },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    picked?.let { uris ->
        ImportDialog(vm, uris, onDismiss = { picked = null })
    }
    renaming?.let { s ->
        TextInputDialog("Rename song", s.name, onDismiss = { renaming = null }) {
            vm.rename(s, it); renaming = null
        }
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${s.name}\"?") },
            text = { Text("The song is removed from the app. Your original audio files are not touched.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(s); deleting = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SongRow(song: Song, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(Icons.Default.LibraryMusic, null) },
        headlineContent = { Text(song.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                "${formatTime(song.durationMs)} · " + song.stems.joinToString(", ") { it.name },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        },
    )
}

@Composable
fun ImportDialog(vm: AppViewModel, uris: List<Uri>, onDismiss: () -> Unit) {
    val names = remember(uris) { vm.displayNames(uris) }
    // Score files are recognised by their extension; everything else is a stem.
    val scoreIndex = remember(names) { names.indexOfFirst { SongRepository.isScoreFile(it) } }
    val audio = remember(names) {
        names.indices.filter { !SongRepository.isScoreFile(names[it]) }
    }
    val (guessedSong, guessedStems) = remember(audio) { SongRepository.guessNames(audio.map { names[it] }) }
    var songName by remember { mutableStateOf(guessedSong) }
    val stemNames = remember { mutableStateListOf(*guessedStems.toTypedArray()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New song") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = songName, onValueChange = { songName = it },
                    label = { Text("Song name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("Stems (${audio.size})", style = MaterialTheme.typography.titleSmall)
                if (audio.isEmpty()) {
                    Text("Select at least one audio file.", color = MaterialTheme.colorScheme.error)
                }
                stemNames.forEachIndexed { i, n ->
                    OutlinedTextField(
                        value = n, onValueChange = { stemNames[i] = it },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("Score", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (scoreIndex >= 0) names[scoreIndex] else "None (you can add one later)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = songName.isNotBlank() && audio.isNotEmpty() && stemNames.all { it.isNotBlank() },
                onClick = {
                    vm.import(
                        songName.trim(),
                        audio.map { uris[it] },
                        stemNames.map { it.trim() },
                        uris.getOrNull(scoreIndex),
                    )
                    onDismiss()
                },
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Audio files plus the types MusicXML files may be reported as (.mxl often has no known type). */
val IMPORT_MIME_TYPES = arrayOf(
    "audio/*",
    "application/vnd.recordare.musicxml",
    "application/vnd.recordare.musicxml+xml",
    "application/xml",
    "text/xml",
    "application/zip",
    "application/octet-stream",
)

// ---------------------------------------------------------------- Player

private enum class Panel { None, Score, Mixer }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: AppViewModel, song: Song) {
    val playing by vm.playing.collectAsStateWithLifecycle()
    val scoreState by vm.score.collectAsStateWithLifecycle()
    val zoom by vm.scoreZoom.collectAsStateWithLifecycle()
    val player = vm.player
    var renamingStem by remember { mutableStateOf<Int?>(null) }
    var confirmRemoveScore by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // Only one of score and mixer is expanded at a time; the score is shown first when there is one.
    var panel by rememberSaveable(song.id) { mutableStateOf(if (song.score != null) Panel.Score else Panel.Mixer) }

    BackHandler { vm.close() }

    LaunchedEffect(song.id, song.score) { vm.loadScore(song) }

    val scorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.setScore(uri)
            panel = Panel.Score
        }
    }

    // Keep the screen awake while practicing.
    val view = LocalView.current
    DisposableEffect(playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }

    val duration = player?.durationMs() ?: 0L
    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(player) {
        while (true) {
            position = player?.positionMs() ?: 0L
            delay(50)
        }
    }

    val ready = scoreState as? ScoreState.Ready
    val sync = remember(ready, duration) { ready?.let { ScoreSync(it.timeline, duration) } }
    // The WebView outlives collapsing the score, so expanding it again is instant.
    val context = LocalContext.current
    val web = remember(ready) {
        ready?.let { ScoreWebView(context, it.xml, it.timeline.measureNumbers, zoom) }
    }
    DisposableEffect(web) { onDispose { web?.destroy() } }
    val currentPosition by rememberUpdatedState(position)
    LaunchedEffect(web, sync) {
        web?.onMeasureTap = { m -> sync?.startOf(m, currentPosition)?.let { vm.seekToMs(it); position = it } }
    }
    val measure = sync?.measureAt(dragging?.let { (it * duration).toLong() } ?: position) ?: -1
    LaunchedEffect(web, measure) { web?.highlight(measure) }
    LaunchedEffect(web, zoom) { web?.setZoom(zoom) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(song.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { vm.close() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (song.score == null) "Add score…" else "Replace score…") },
                                onClick = { menu = false; scorePicker.launch(arrayOf("*/*")) },
                            )
                            if (song.score != null) {
                                DropdownMenuItem(
                                    text = { Text("Remove score") },
                                    onClick = { menu = false; confirmRemoveScore = true },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Slider(
                        value = dragging ?: if (duration > 0) position.toFloat() / duration else 0f,
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            dragging?.let { vm.seekToMs((it * duration).toLong()) }
                            dragging = null
                        },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val shown = dragging?.let { (it * duration).toLong() } ?: position
                        Text(formatTime(shown), style = MaterialTheme.typography.labelMedium)
                        Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PanelToggle("Score", Icons.Default.MusicNote, panel == Panel.Score) {
                            panel = if (panel == Panel.Score) Panel.None else Panel.Score
                        }
                        IconButton(onClick = { vm.seekToMs(0) }) {
                            Icon(Icons.Default.SkipPrevious, "Back to start")
                        }
                        IconButton(onClick = { vm.seekToMs(position - 5000) }) {
                            Icon(Icons.Default.Replay5, "Back 5 seconds")
                        }
                        FilledIconButton(onClick = vm::togglePlay, modifier = Modifier.size(64.dp)) {
                            Icon(
                                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (playing) "Pause" else "Play",
                                modifier = Modifier.size(36.dp),
                            )
                        }
                        IconButton(onClick = { vm.seekToMs(position + 5000) }) {
                            Icon(Icons.Default.Forward5, "Forward 5 seconds")
                        }
                        PanelToggle("Mixer", Icons.Default.Tune, panel == Panel.Mixer) {
                            panel = if (panel == Panel.Mixer) Panel.None else Panel.Mixer
                        }
                    }
                }
            }
        },
    ) { pad ->
        val content = Modifier.padding(pad).fillMaxSize()
        when (panel) {
            Panel.Score -> when (val st = scoreState) {
                is ScoreState.Ready -> if (web != null) {
                    ScorePanel(web, zoom, onZoom = vm::setScoreZoom, modifier = content)
                }
                ScoreState.Loading -> Box(content, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is ScoreState.Failed -> Message(content, st.message, "Choose another score") {
                    scorePicker.launch(arrayOf("*/*"))
                }
                ScoreState.None -> Message(
                    content,
                    "This song has no score yet.\nAdd its MusicXML file (.mxl or .musicxml, e.g. exported " +
                        "from MuseScore) to follow along and tap a measure to jump to it.",
                    "Add score",
                ) { scorePicker.launch(arrayOf("*/*")) }
            }
            Panel.Mixer -> LazyColumn(content, contentPadding = PaddingValues(12.dp)) {
                itemsIndexed(song.stems) { i, stem ->
                    StemCard(
                        stem,
                        onVolume = { vm.setVolume(i, it) },
                        onVolumeDone = vm::persistCurrent,
                        onMute = { vm.toggleMute(i) },
                        onSolo = { vm.solo(i) },
                        onRename = { renamingStem = i },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Panel.None -> Box(content)
        }
    }

    renamingStem?.let { i ->
        TextInputDialog("Rename stem", song.stems[i].name, onDismiss = { renamingStem = null }) {
            vm.renameStem(i, it); renamingStem = null
        }
    }
    if (confirmRemoveScore) {
        AlertDialog(
            onDismissRequest = { confirmRemoveScore = false },
            title = { Text("Remove the score?") },
            text = { Text("The score is removed from this song. Your original file is not touched.") },
            confirmButton = {
                TextButton(onClick = { vm.removeScore(); confirmRemoveScore = false }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveScore = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PanelToggle(label: String, icon: ImageVector, expanded: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconToggleButton(checked = expanded, onCheckedChange = { onClick() }) {
            Icon(icon, if (expanded) "Collapse $label" else "Expand $label")
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Message(modifier: Modifier, text: String, action: String, onAction: () -> Unit) {
    Column(
        modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAction) { Text(action) }
    }
}

@Composable
fun StemCard(
    stem: Stem,
    onVolume: (Float) -> Unit,
    onVolumeDone: () -> Unit,
    onMute: () -> Unit,
    onSolo: () -> Unit,
    onRename: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stem.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable(onClick = onRename),
                )
                Text(
                    if (stem.muted) "muted" else "${(stem.volume * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onSolo) { Text("Solo") }
                IconToggleButton(checked = stem.muted, onCheckedChange = { onMute() }) {
                    Icon(
                        if (stem.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        if (stem.muted) "Unmute" else "Mute",
                        tint = if (stem.muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Slider(
                value = stem.volume,
                onValueChange = onVolume,
                onValueChangeFinished = onVolumeDone,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

@Composable
fun TextInputDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun formatTime(ms: Long): String {
    val s = maxOf(ms, 0) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
