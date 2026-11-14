package com.watchoutrf.desktop.data.shows

import com.watchoutrf.desktop.domain.model.FrequencyRange
import com.watchoutrf.desktop.domain.model.GainMode
import com.watchoutrf.desktop.domain.model.MarkerColor
import com.watchoutrf.desktop.domain.model.ScanMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

/** A marker as stored in a show (no live amplitude, no runtime id). */
data class ShowMarker(
    val frequencyHz: Long,
    val label: String,
    val color: MarkerColor,
)

/**
 * Everything needed to bring the analyzer back to how it was set up for one show:
 * the scanned range, scan settings and the named markers (mics, IEMs, …).
 */
data class ShowConfig(
    val name: String,
    val notes: String = "",
    val savedAtMillis: Long = System.currentTimeMillis(),
    /** SDR dongle that was connected when the show was saved (informational). */
    val deviceName: String? = null,
    val frequencyRange: FrequencyRange,
    val numBins: Int,
    val scanMode: ScanMode,
    val maxHoldEnabled: Boolean,
    val showAutoPeaks: Boolean,
    val gainMode: GainMode,
    val manualGainDb: Int,
    val markers: List<ShowMarker>,
) {
    fun formatSavedAt(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(savedAtMillis))

    /** True when every word of [query] appears in the name, notes or a marker label. */
    fun matches(query: String): Boolean {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystack = (listOf(name, notes, frequencyRange.name, formatSavedAt()) + markers.map { it.label })
            .joinToString("\n").lowercase()
        return words.all { it in haystack }
    }
}

/**
 * Stores shows as one small text file each (Java properties format) so they are easy
 * to back up or copy to another computer.
 */
class ShowRepository(private val directory: File = defaultDirectory()) {

    /** All saved shows, most recently saved first. Unreadable files are skipped. */
    fun list(): List<ShowConfig> =
        showFiles().mapNotNull { read(it) }.sortedByDescending { it.savedAtMillis }

    /**
     * Saves [show]. A show with the same name (ignoring case) is replaced.
     * @return null on success, otherwise an error message.
     */
    fun save(show: ShowConfig): String? {
        val name = show.name.trim()
        if (name.isEmpty()) return "Give the show a name."
        return try {
            directory.mkdirs()
            val file = fileFor(name) ?: newFileFor(name)
            val props = Properties().apply {
                setProperty("name", name)
                setProperty("notes", show.notes)
                setProperty("savedAt", show.savedAtMillis.toString())
                show.deviceName?.let { setProperty("device", it) }
                setProperty("range.name", show.frequencyRange.name)
                setProperty("range.startHz", show.frequencyRange.startHz.toString())
                setProperty("range.endHz", show.frequencyRange.endHz.toString())
                setProperty("numBins", show.numBins.toString())
                setProperty("scanMode", show.scanMode.name)
                setProperty("maxHold", show.maxHoldEnabled.toString())
                setProperty("autoPeaks", show.showAutoPeaks.toString())
                setProperty("gainMode", show.gainMode.name)
                setProperty("manualGainDb", show.manualGainDb.toString())
                setProperty("marker.count", show.markers.size.toString())
                show.markers.forEachIndexed { i, m ->
                    setProperty("marker.$i.frequencyHz", m.frequencyHz.toString())
                    setProperty("marker.$i.label", m.label)
                    setProperty("marker.$i.color", m.color.name)
                }
            }
            file.writer(Charsets.UTF_8).use { props.store(it, "WatchoutRF show") }
            null
        } catch (e: Exception) {
            "Could not save the show: ${e.message}"
        }
    }

    /** @return true when a show with that name existed and was deleted. */
    fun delete(name: String): Boolean = fileFor(name.trim())?.delete() ?: false

    fun exists(name: String): Boolean = fileFor(name.trim()) != null

    private fun showFiles(): List<File> =
        directory.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }?.toList() ?: emptyList()

    private fun fileFor(name: String): File? =
        showFiles().firstOrNull { read(it)?.name.equals(name, ignoreCase = true) }

    private fun newFileFor(name: String): File {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "show" }.take(60)
        var candidate = File(directory, slug + EXTENSION)
        var n = 2
        while (candidate.exists()) candidate = File(directory, "$slug-${n++}$EXTENSION")
        return candidate
    }

    private fun read(file: File): ShowConfig? = try {
        val p = Properties()
        file.reader(Charsets.UTF_8).use { p.load(it) }
        val count = p.getProperty("marker.count", "0").toInt()
        ShowConfig(
            name = p.getProperty("name"),
            notes = p.getProperty("notes", ""),
            savedAtMillis = p.getProperty("savedAt", file.lastModified().toString()).toLong(),
            deviceName = p.getProperty("device"),
            frequencyRange = FrequencyRange(
                startHz = p.getProperty("range.startHz").toLong(),
                endHz = p.getProperty("range.endHz").toLong(),
                name = p.getProperty("range.name", "Custom"),
            ),
            numBins = p.getProperty("numBins", "2048").toInt(),
            scanMode = enumOr(p.getProperty("scanMode"), ScanMode.CONTINUOUS),
            maxHoldEnabled = p.getProperty("maxHold", "true").toBoolean(),
            showAutoPeaks = p.getProperty("autoPeaks", "true").toBoolean(),
            gainMode = enumOr(p.getProperty("gainMode"), GainMode.Auto),
            manualGainDb = p.getProperty("manualGainDb", "40").toInt(),
            markers = (0 until count).map { i ->
                ShowMarker(
                    frequencyHz = p.getProperty("marker.$i.frequencyHz").toLong(),
                    label = p.getProperty("marker.$i.label", "M${i + 1}"),
                    color = enumOr(p.getProperty("marker.$i.color"), MarkerColor.CYAN),
                )
            },
        ).takeIf { it.frequencyRange.endHz > it.frequencyRange.startHz }
    } catch (e: Exception) {
        null // damaged or foreign file: ignore it rather than break the list
    }

    private inline fun <reified T : Enum<T>> enumOr(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    companion object {
        private const val EXTENSION = ".show"

        /** Per-user data folder, following each operating system's convention. */
        fun defaultDirectory(): File {
            val home = System.getProperty("user.home")
            val os = System.getProperty("os.name").lowercase()
            val base = when {
                "mac" in os -> File(home, "Library/Application Support")
                "win" in os -> System.getenv("APPDATA")?.let { File(it) } ?: File(home, "AppData/Roaming")
                else -> File(home, ".config")
            }
            return File(base, "WatchoutRF/shows")
        }
    }
}
