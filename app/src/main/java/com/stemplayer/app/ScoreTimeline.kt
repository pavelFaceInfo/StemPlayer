package com.stemplayer.app

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * When each measure of a MusicXML score is played: measures are unrolled in playback order
 * (repeats, voltas, D.C./D.S., Fine, Coda) and timed using the score's tempo markings.
 */
class ScoreTimeline(
    /** MusicXML `number` attribute of each measure, in document order. */
    val measureNumbers: List<String>,
    /** Measures in playback order with their start/end in score seconds. */
    val segments: List<Segment>,
) {
    data class Segment(val measure: Int, val startSec: Double, val endSec: Double)

    val totalSec: Double get() = segments.lastOrNull()?.endSec ?: 0.0

    companion object {
        /** Accepts compressed (.mxl) or plain MusicXML bytes and returns the MusicXML text. */
        fun extractXml(bytes: ByteArray): String {
            val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
            if (!isZip) return decodeText(bytes)
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (!e.isDirectory) entries[e.name] = zip.readBytes()
                }
            }
            val root = entries["META-INF/container.xml"]?.let { c ->
                Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(decodeText(c))?.groupValues?.get(1)
            }
            val data = root?.let { entries[it] }
                ?: entries.entries.firstOrNull { (n, _) ->
                    !n.startsWith("META-INF") && (n.endsWith(".xml") || n.endsWith(".musicxml"))
                }?.value
                ?: throw IllegalArgumentException("No MusicXML found inside the .mxl file")
            return decodeText(data)
        }

        private fun decodeText(b: ByteArray): String {
            // MusicXML is UTF-8 or UTF-16 (with BOM).
            if (b.size >= 2 && ((b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) || (b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()))) {
                return String(b, Charsets.UTF_16)
            }
            return String(b, Charsets.UTF_8).removePrefix("\uFEFF")
        }

        fun parse(xml: String): ScoreTimeline {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
            }
            val builder = factory.newDocumentBuilder()
            // MusicXML references an online DTD: never fetch it.
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            val doc = builder.parse(InputSource(StringReader(xml)))
            val root = doc.documentElement
            require(root.tagName == "score-partwise") {
                if (root.tagName == "score-timewise") "Timewise MusicXML is not supported; export it as partwise"
                else "Not a MusicXML score"
            }
            val parts = root.children("part")
            require(parts.isNotEmpty()) { "The score has no parts" }
            val partMeasures = parts.map { it.children("measure") }
            val first = partMeasures[0]
            require(first.isNotEmpty()) { "The score has no measures" }

            // Structure (repeats, tempo, jumps) comes from the first part; a measure lasts as long
            // as its longest part.
            val results = partMeasures.mapIndexed { p, measures -> readPart(measures, collectInfo = p == 0) }
            val infos = results[0].infos
            val lengths = DoubleArray(first.size) { i -> results.maxOf { it.lengths.getOrElse(i) { 0.0 } } }
            for (i in lengths.indices) if (lengths[i] <= 0.0) lengths[i] = infos[i].timeSigQuarters

            val order = playbackOrder(infos)

            var tempo = infos.firstNotNullOfOrNull { it.tempos.firstOrNull()?.second } ?: 120.0
            var t = 0.0
            val segments = order.map { m ->
                val start = t
                var off = 0.0
                for ((at, bpm) in infos[m].tempos) {
                    val a = at.coerceIn(off, lengths[m])
                    t += (a - off) * 60.0 / tempo
                    off = a
                    tempo = bpm
                }
                t += (lengths[m] - off) * 60.0 / tempo
                Segment(m, start, t)
            }
            return ScoreTimeline(infos.map { it.number }, segments)
        }

        private class MeasureInfo(val number: String) {
            var timeSigQuarters = 4.0
            /** (offset in quarters, quarter notes per minute) */
            val tempos = mutableListOf<Pair<Double, Double>>()
            var forward = false
            var backwardTimes = 0
            var endingStart: Set<Int>? = null
            var endingStop = false
            var segno = false
            var coda = false
            var daCapo = false
            var dalSegno = false
            var toCoda = false
            var fine = false
        }

        private class PartResult(val lengths: DoubleArray, val infos: List<MeasureInfo>)

        private fun readPart(measures: List<Element>, collectInfo: Boolean): PartResult {
            var divisions = 1.0
            var timeSig = 4.0
            val lengths = DoubleArray(measures.size)
            val infos = ArrayList<MeasureInfo>()
            for ((idx, m) in measures.withIndex()) {
                val info = MeasureInfo(m.getAttribute("number"))
                var cursor = 0.0
                var maxCursor = 0.0
                for (c in m.children()) {
                    when (c.tagName) {
                        "attributes" -> {
                            c.child("divisions")?.text()?.toDoubleOrNull()?.let { if (it > 0) divisions = it }
                            c.child("time")?.let { time ->
                                val beats = time.child("beats")?.text()?.split('+')
                                    ?.sumOf { it.trim().toDoubleOrNull() ?: 0.0 } ?: 0.0
                                val type = time.child("beat-type")?.text()?.toDoubleOrNull() ?: 0.0
                                if (beats > 0 && type > 0) timeSig = beats * 4.0 / type
                            }
                        }
                        "note" -> {
                            if (c.child("grace") == null && c.child("chord") == null) {
                                cursor += (c.child("duration")?.text()?.toDoubleOrNull() ?: 0.0) / divisions
                            }
                        }
                        "backup" -> cursor -= (c.child("duration")?.text()?.toDoubleOrNull() ?: 0.0) / divisions
                        "forward" -> cursor += (c.child("duration")?.text()?.toDoubleOrNull() ?: 0.0) / divisions
                        "direction" -> if (collectInfo) {
                            val offset = cursor + (c.child("offset")?.text()?.toDoubleOrNull() ?: 0.0) / divisions
                            val sound = c.child("sound")
                            if (sound != null) readSound(sound, offset, info)
                            if (sound?.getAttribute("tempo").isNullOrEmpty()) {
                                metronomeTempo(c)?.let { info.tempos += offset to it }
                            }
                        }
                        "sound" -> if (collectInfo) readSound(c, cursor, info)
                        "barline" -> if (collectInfo) {
                            c.child("repeat")?.let { r ->
                                if (r.getAttribute("direction") == "forward") info.forward = true
                                if (r.getAttribute("direction") == "backward") {
                                    info.backwardTimes = r.getAttribute("times").toIntOrNull()?.coerceAtLeast(1) ?: 2
                                }
                            }
                            c.child("ending")?.let { e ->
                                when (e.getAttribute("type")) {
                                    "start" -> info.endingStart = Regex("\\d+").findAll(e.getAttribute("number"))
                                        .map { it.value.toInt() }.toSet().ifEmpty { setOf(1) }
                                    "stop", "discontinue" -> info.endingStop = true
                                }
                            }
                            c.child("sound")?.let { readSound(it, cursor, info) }
                        }
                    }
                    maxCursor = maxOf(maxCursor, cursor)
                }
                info.timeSigQuarters = timeSig
                info.tempos.sortBy { it.first }
                lengths[idx] = maxCursor
                infos += info
            }
            return PartResult(lengths, infos)
        }

        private fun readSound(s: Element, offset: Double, info: MeasureInfo) {
            s.getAttribute("tempo").toDoubleOrNull()?.let { if (it > 0) info.tempos += offset to it }
            if (s.hasAttribute("segno")) info.segno = true
            if (s.hasAttribute("coda")) info.coda = true
            if (s.getAttribute("dacapo") == "yes") info.daCapo = true
            if (s.hasAttribute("dalsegno")) info.dalSegno = true
            if (s.hasAttribute("tocoda")) info.toCoda = true
            if (s.hasAttribute("fine")) info.fine = true
        }

        /** Tempo from a metronome mark such as "half = 60", in quarter notes per minute. */
        private fun metronomeTempo(direction: Element): Double? {
            val met = direction.children("direction-type").firstNotNullOfOrNull { it.child("metronome") } ?: return null
            val perMinute = met.child("per-minute")?.text()?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
                ?: return null
            var quarters = when (met.child("beat-unit")?.text()) {
                "whole" -> 4.0; "half" -> 2.0; "quarter" -> 1.0; "eighth" -> 0.5; "16th" -> 0.25
                else -> 1.0
            }
            if (met.child("beat-unit-dot") != null) quarters *= 1.5
            return perMinute * quarters
        }

        private fun playbackOrder(ms: List<MeasureInfo>): List<Int> {
            val n = ms.size
            // Propagate volta numbers from their start to their stop measure.
            val ending = arrayOfNulls<Set<Int>>(n)
            val blocks = mutableListOf<IntRange>()
            var cur: Set<Int>? = null
            var blockStart = 0
            for (i in 0 until n) {
                ms[i].endingStart?.let { cur = it; blockStart = i }
                ending[i] = cur
                if (cur != null && ms[i].endingStop) {
                    blocks += blockStart..i
                    cur = null
                }
            }
            if (cur != null) blocks += blockStart until n
            // The final volta of a group is played when repeats are skipped after D.C./D.S.
            val finalVolta = BooleanArray(n)
            for ((k, b) in blocks.withIndex()) {
                val next = blocks.getOrNull(k + 1)
                if (next == null || next.first != b.last + 1) for (i in b) finalVolta[i] = true
            }
            val segno = ms.indexOfFirst { it.segno }
            val codas = ms.indices.filter { ms[it].coda }

            val order = ArrayList<Int>()
            val backCount = IntArray(n)
            val jumpsTaken = HashSet<Int>()
            var i = 0
            var repeatStart = 0
            var pass = 1
            var arrivedByRepeat = false
            var resetPending = false
            var afterJump = false
            var steps = 0
            while (i in 0 until n && steps++ < 20_000) {
                val m = ms[i]
                val volta = ending[i]
                if (volta == null && resetPending) {
                    resetPending = false
                    pass = 1
                    repeatStart = i
                }
                if (m.forward && !arrivedByRepeat) {
                    repeatStart = i
                    pass = 1
                    resetPending = false
                }
                arrivedByRepeat = false
                if (volta != null) {
                    val skip = if (afterJump) !finalVolta[i] else pass !in volta
                    if (skip) { i++; continue }
                }
                order += i
                if (afterJump && m.fine) break
                if (afterJump && m.toCoda) {
                    val target = codas.firstOrNull { it > i }
                    if (target != null) { i = target; continue }
                }
                if (m.backwardTimes > 0 && !afterJump) {
                    backCount[i]++
                    if (backCount[i] < m.backwardTimes) {
                        pass++
                        i = repeatStart
                        arrivedByRepeat = true
                        continue
                    }
                    backCount[i] = 0
                    resetPending = true
                }
                if ((m.daCapo || (m.dalSegno && segno >= 0)) && jumpsTaken.add(i)) {
                    afterJump = true
                    i = if (m.daCapo) 0 else segno
                    repeatStart = i
                    pass = 1
                    resetPending = false
                    continue
                }
                i++
            }
            return order
        }

        private fun Element.children(tag: String? = null): List<Element> {
            val out = ArrayList<Element>()
            val nodes = childNodes
            for (k in 0 until nodes.length) {
                val node = nodes.item(k)
                if (node is Element && (tag == null || node.tagName == tag)) out += node
            }
            return out
        }

        private fun Element.child(tag: String): Element? {
            val nodes = childNodes
            for (k in 0 until nodes.length) {
                val node = nodes.item(k)
                if (node is Element && node.tagName == tag) return node
            }
            return null
        }

        private fun Element.text(): String = textContent.trim()
    }
}

/** Maps between audio time and score measures for one song. */
class ScoreSync(val timeline: ScoreTimeline, audioDurationMs: Long) {
    /**
     * Audio exported from the same score matches the score's tempo, usually followed by a short tail
     * of silence; then score time is used as is. Otherwise the score is stretched to the audio length.
     */
    private val msPerSec: Double = run {
        val scoreMs = timeline.totalSec * 1000
        if (scoreMs <= 0 || audioDurationMs <= 0) return@run 1000.0
        val tail = audioDurationMs - scoreMs
        if (tail >= -250 && tail <= maxOf(5000.0, audioDurationMs * 0.04)) 1000.0
        else 1000.0 * audioDurationMs / scoreMs
    }

    private val starts = timeline.segments.map { it.startSec * msPerSec }

    /** Measure (document index) playing at [ms], or -1. */
    fun measureAt(ms: Long): Int {
        if (starts.isEmpty()) return -1
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= ms) lo = mid else hi = mid - 1
        }
        return timeline.segments[lo].measure
    }

    /** Start of [measure] in ms; with repeats, the occurrence nearest to [currentMs]. */
    fun startOf(measure: Int, currentMs: Long): Long? =
        timeline.segments.indices.filter { timeline.segments[it].measure == measure }
            .minByOrNull { kotlin.math.abs(starts[it] - currentMs) }
            ?.let { starts[it].toLong() }
}
