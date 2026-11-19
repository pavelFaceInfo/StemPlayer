package com.stemplayer.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportState(val songName: String, val done: Int, val total: Int)

sealed interface ScoreState {
    data object None : ScoreState
    data object Loading : ScoreState
    data class Ready(val songId: String, val xml: String, val timeline: ScoreTimeline) : ScoreState
    data class Failed(val message: String) : ScoreState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SongRepository(app)

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs

    val current: StateFlow<Song?> = Playback.current
    val playing: StateFlow<Boolean> = Playback.playing

    private val _importing = MutableStateFlow<ImportState?>(null)
    val importing: StateFlow<ImportState?> = _importing

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    val player: MixerPlayer? get() = Playback.player

    private val _score = MutableStateFlow<ScoreState>(ScoreState.None)
    val score: StateFlow<ScoreState> = _score

    private val prefs = app.getSharedPreferences("settings", 0)
    private val _scoreZoom = MutableStateFlow(prefs.getInt("scoreZoom", 35))
    val scoreZoom: StateFlow<Int> = _scoreZoom

    init {
        Playback.init(app)
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            _songs.value = withContext(Dispatchers.IO) { repo.loadAll() }
        }
    }

    fun clearError() { _error.value = null }

    fun displayNames(uris: List<Uri>): List<String> = uris.map { repo.displayName(it) }

    fun import(name: String, uris: List<Uri>, stemNames: List<String>, scoreUri: Uri?) {
        if (_importing.value != null) return
        // Order stems S-A-T-B when the names make that possible; otherwise keep the picked order.
        val ordered = uris.zip(stemNames).withIndex()
            .sortedWith(compareBy({ SongRepository.voiceOrder(it.value.second) }, { it.index }))
            .map { it.value }
        _importing.value = ImportState(name, 0, uris.size)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repo.import(name, ordered.map { it.first }, ordered.map { it.second }, scoreUri) { done, total ->
                        _importing.value = ImportState(name, done, total)
                    }
                }
                refresh()
            } catch (e: Exception) {
                _error.value = e.message ?: "Import failed"
            } finally {
                _importing.value = null
            }
        }
    }

    fun open(song: Song) {
        try {
            Playback.open(song, repo.songDir(song.id))
        } catch (e: Exception) {
            _error.value = "Could not open song: ${e.message}"
        }
    }

    fun close() {
        Playback.close()
        _score.value = ScoreState.None
        refresh()
    }

    /** Loads (and times) the score of [song]; called whenever the open song or its score changes. */
    fun loadScore(song: Song, force: Boolean = false) {
        val cur = _score.value
        if (song.score == null) { _score.value = ScoreState.None; return }
        if (!force && cur is ScoreState.Ready && cur.songId == song.id) return
        _score.value = ScoreState.Loading
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val xml = repo.readScore(song) ?: return@withContext ScoreState.Failed("The score file is missing")
                    ScoreState.Ready(song.id, xml, ScoreTimeline.parse(xml))
                } catch (e: Exception) {
                    ScoreState.Failed("Could not read the score: ${e.message}")
                }
            }
            if (current.value?.id == song.id) _score.value = result
        }
    }

    fun setScore(uri: Uri) {
        val song = current.value ?: return
        _score.value = ScoreState.Loading
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { repo.saveScore(song.id, uri) }
                val latest = current.value?.copy(score = file) ?: return@launch
                commit(latest, persist = true)
                loadScore(latest, force = true)
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not add the score"
                loadScore(song, force = true)
            }
        }
    }

    fun removeScore() {
        val song = current.value ?: return
        commit(song.copy(score = null), persist = true)
        _score.value = ScoreState.None
        viewModelScope.launch(Dispatchers.IO) { repo.deleteScore(song.id) }
    }

    fun setScoreZoom(z: Int) {
        _scoreZoom.value = z
        prefs.edit().putInt("scoreZoom", z).apply()
    }

    fun togglePlay() = Playback.toggle()
    fun seekToMs(ms: Long) = Playback.seekToMs(ms)

    fun setVolume(index: Int, volume: Float) = updateStem(index, persist = false) { it.copy(volume = volume) }
    fun toggleMute(index: Int) = updateStem(index, persist = true) { it.copy(muted = !it.muted) }

    fun solo(index: Int) {
        val song = current.value ?: return
        val alreadySolo = song.stems.withIndex().all { (i, s) -> s.muted == (i != index) }
        val updated = song.copy(stems = song.stems.mapIndexed { i, s ->
            s.copy(muted = if (alreadySolo) false else i != index)
        })
        commit(updated, persist = true)
    }

    fun persistCurrent() {
        val song = current.value ?: return
        viewModelScope.launch(Dispatchers.IO) { repo.save(song) }
    }

    private fun updateStem(index: Int, persist: Boolean, f: (Stem) -> Stem) {
        val song = current.value ?: return
        commit(song.copy(stems = song.stems.mapIndexed { i, s -> if (i == index) f(s) else s }), persist)
    }

    private fun commit(song: Song, persist: Boolean) {
        Playback.update(song)
        if (persist) persistCurrent()
    }

    fun rename(song: Song, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.save(song.copy(name = newName))
            _songs.update { list -> list.map { if (it.id == song.id) it.copy(name = newName) else it } }
        }
    }

    fun renameStem(index: Int, newName: String) = updateStem(index, persist = true) { it.copy(name = newName) }

    fun delete(song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.delete(song.id)
            _songs.update { list -> list.filter { it.id != song.id } }
        }
    }
}
