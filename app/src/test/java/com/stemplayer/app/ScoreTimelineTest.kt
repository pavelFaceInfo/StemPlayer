package com.stemplayer.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ScoreTimelineTest {

    private fun measure(n: Int, extra: String = "", attrs: String = "") = """
        <measure number="$n">$attrs$extra
          <note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration></note>
        </measure>"""

    private fun score(vararg measures: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">
        <score-partwise version="4.0"><part-list/><part id="P1">${measures.joinToString("")}</part></score-partwise>"""

    @Test
    fun repeatsWithVoltasAndTempo() {
        // 2/4 at 60 bpm: every measure (2 quarters) lasts 2 s.
        val attrs = "<attributes><divisions>2</divisions><time><beats>2</beats><beat-type>4</beat-type></time></attributes>" +
            "<direction><direction-type><words>x</words></direction-type><sound tempo=\"60\"/></direction>"
        val xml = score(
            measure(1, attrs = attrs),
            measure(2, "<barline location=\"left\"><repeat direction=\"forward\"/></barline>"),
            measure(3, "<barline location=\"left\"><ending number=\"1\" type=\"start\"/></barline>" +
                "<barline location=\"right\"><ending number=\"1\" type=\"stop\"/><repeat direction=\"backward\"/></barline>"),
            measure(4, "<barline location=\"left\"><ending number=\"2\" type=\"start\"/></barline>" +
                "<barline location=\"right\"><ending number=\"2\" type=\"discontinue\"/></barline>"),
            measure(5),
        )
        val t = ScoreTimeline.parse(xml)
        assertEquals(listOf(0, 1, 2, 1, 3, 4), t.segments.map { it.measure })
        assertEquals(12.0, t.totalSec, 1e-9)

        val sync = ScoreSync(t, 12_000)
        assertEquals(2, sync.measureAt(4_500))
        assertEquals(1, sync.measureAt(6_100))
        assertEquals(3, sync.measureAt(8_000))
        assertEquals(2_000L, sync.startOf(1, 3_000))
        assertEquals(6_000L, sync.startOf(1, 7_000))
        // Audio of a different tempo: stretched to fit.
        assertEquals(1, ScoreSync(t, 24_000).measureAt(12_500))
    }

    @Test
    fun daCapoAlFine() {
        val attrs = "<attributes><divisions>2</divisions><time><beats>2</beats><beat-type>4</beat-type></time></attributes>"
        val xml = score(
            measure(1, attrs = attrs),
            measure(2, "<direction><direction-type><words>Fine</words></direction-type><sound fine=\"yes\"/></direction>"),
            measure(3, "<direction><direction-type><words>D.C.</words></direction-type><sound dacapo=\"yes\"/></direction>"),
        )
        assertEquals(listOf(0, 1, 2, 0, 1), ScoreTimeline.parse(xml).segments.map { it.measure })
    }

    @Test
    fun realScoreIfProvided() {
        val path = System.getenv("STEMPLAYER_TEST_MXL") ?: return
        val t = ScoreTimeline.parse(ScoreTimeline.extractXml(File(path).readBytes()))
        println("measures=${t.measureNumbers.size} played=${t.segments.size} total=${"%.1f".format(t.totalSec)}s")
        println(t.segments.joinToString(" ") { t.measureNumbers[it.measure] })
    }
}
