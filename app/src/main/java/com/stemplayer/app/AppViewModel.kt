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

    fun import(name: String, uris: List<Uri>, stemNames: List<String>) {
        if (_importing.value != null) return
        // Order stems S-A-T-B when the names make that possible; otherwise keep the picked order.
        val ordered = uris.zip(stemNames).withIndex()
            .sortedWith(compareBy({ SongRepository.voiceOrder(it.value.second) }, { it.index }))
            .map { it.value }
        _importing.value = ImportState(name, 0, uris.size)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repo.import(name, ordered.map { it.first }, ordered.map { it.second }) { done, total ->
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
        refresh()
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
