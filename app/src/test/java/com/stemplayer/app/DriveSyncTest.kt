package com.stemplayer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveSyncTest {

    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test
    fun parsesRootFolderView() {
        val entries = DriveClient.parseFolderView(resource("drive_root_folder.html"))
        assertEquals(5, entries.size)
        assertTrue(entries.all { it.isFolder })
        assertEquals("Răspunsurile mari 4", entries.last().name)
        assertEquals("1Jm65VU8Od0C_7D3nC0z4Xjk9PdodgxXY", entries.last().id)
    }

    @Test
    fun parsesSongFolderView() {
        val entries = DriveClient.parseFolderView(resource("drive_song_folder.html"))
        assertEquals(5, entries.size)
        assertTrue(entries.none { it.isFolder })
        assertEquals("Acum slobozește - Golovko.mxl", entries[0].name)
        assertEquals("1lslsy7tvGCYNneVABhHHm24gtb0jt4l-", entries[3].id)
    }

    @Test
    fun decodesEntities() {
        assertEquals("Tom & Jerry's \"song\" é", DriveClient.decodeEntities("Tom &amp; Jerry&#39;s &quot;song&quot; &#xe9;"))
    }

    // ------------------------------------------------------------ planner

    private fun folder(name: String, prefix: String, v: String = "1", score: String? = null) = RemoteFolder(
        id = "f-$name",
        name = name,
        audio = listOf("Alto", "Bass", "Soprano", "Tenor").map { RemoteFile("$prefix$it", "$prefix-$it.mp3", "$it@$v") },
        score = score?.let { RemoteFile("score", "$name.mxl", it) },
    )

    private fun localFrom(f: RemoteFolder, id: String = "local-${f.name}") = Song(
        id = id, name = f.name, sampleRate = 44100, durationMs = 1000, createdAt = 0,
        stems = f.audio.map { Stem(name = it.name.substringAfterLast('-').substringBefore('.'), file = "x", source = it.name, version = it.version) },
        score = f.score?.let { "score.musicxml" }, scoreSource = f.score?.name, scoreVersion = f.score?.version,
    )

    @Test
    fun newFolderIsNewSong() {
        val plan = SyncPlanner.plan(emptyList(), listOf(folder("Colind", "Colind (English)")))
        assertEquals(1, plan.newSongs.size)
        assertTrue(plan.updates.isEmpty())
    }

    @Test
    fun unchangedSongIsSkipped() {
        val f = folder("Colind", "Colind (English)", score = "s1")
        val plan = SyncPlanner.plan(listOf(localFrom(f).copy(name = "Renamed by user")), listOf(f))
        assertTrue(plan.newSongs.isEmpty())
        assertTrue(plan.updates.isEmpty())
    }

    @Test
    fun changedFilesAreAnUpdate() {
        val old = folder("Colind", "Colind (English)", v = "1", score = "s1")
        val new = folder("Colind", "Colind (English)", v = "2", score = "s2")
        val plan = SyncPlanner.plan(listOf(localFrom(old)), listOf(new))
        assertTrue(plan.newSongs.isEmpty())
        val u = plan.updates.single()
        assertEquals("local-Colind", u.song.id)
        assertTrue("score changed" in u.changes)
        assertTrue("Alto changed" in u.changes)
        // Keeping the app's copy records the Drive versions, so it isn't offered again.
        assertTrue(SyncPlanner.plan(listOf(u.keepMine), listOf(new)).updates.isEmpty())
    }

    @Test
    fun renamedStemsMeanNewSong() {
        val old = folder("Colind", "Colind (English)")
        val plan = SyncPlanner.plan(listOf(localFrom(old)), listOf(folder("Colind", "Colind v2")))
        assertEquals(1, plan.newSongs.size)
        assertTrue(plan.updates.isEmpty())
    }

    @Test
    fun songImportedBeforeSourcesWereRecordedIsMatchedByNames() {
        val f = folder("Colind", "Colind (English)")
        val legacy = localFrom(f).let { s -> s.copy(name = "Colind (English)", stems = s.stems.map { it.copy(source = null, version = null) }) }
        val plan = SyncPlanner.plan(listOf(legacy), listOf(f))
        assertTrue(plan.newSongs.isEmpty())
        val u = plan.updates.single()
        assertEquals(f.audio.map { it.name }.sorted(), u.keepMine.stems.map { it.source }.sortedBy { it })
        assertNull(SyncPlanner.plan(listOf(u.keepMine), listOf(f)).updates.firstOrNull())
    }
}
