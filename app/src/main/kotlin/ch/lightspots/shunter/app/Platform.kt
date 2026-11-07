package ch.lightspots.shunter.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes

private val logger = KotlinLogging.logger {}

/** Opening folders and web pages with the desktop's default applications. */
object Launcher {
    /** Opens [path] in the file manager. Returns false when no application could be started. */
    fun openFolder(path: Path): Boolean = open(path.toString()) { Desktop.getDesktop().open(path.toFile()) }

    fun openUrl(url: String): Boolean {
        // Only web pages: URLs come from feeds and mod files
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            logger.warn { "Not opening $url, not a web address" }
            return false
        }
        return open(url) { Desktop.getDesktop().browse(URI(url)) }
    }

    // xdg-open first: AWT's Desktop needs GNOME libraries and is missing on many Linux desktops
    private fun open(target: String, awt: () -> Unit): Boolean {
        try {
            ProcessBuilder("xdg-open", target).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            return true
        } catch (e: IOException) {
            logger.info { "xdg-open not available (${e.message}), trying AWT" }
        }
        return try {
            if (!Desktop.isDesktopSupported()) return false
            awt()
            true
        } catch (e: Exception) {
            logger.warn(e) { "Could not open $target" }
            false
        }
    }

    /** Asks for a mod archive. Null when the dialog was cancelled. */
    fun chooseArchive(parent: Frame?): Path? {
        val dialog = FileDialog(parent, "Install mod archive", FileDialog.LOAD).apply {
            setFilenameFilter { _, name -> ARCHIVE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) } }
            directory = Path(System.getProperty("user.home"), "Downloads").takeIf { it.exists() }?.toString()
            isVisible = true
        }
        val file = dialog.file ?: return null
        return Path(dialog.directory ?: "", file).takeIf { it.isRegularFile() }
    }

    val ARCHIVE_EXTENSIONS = listOf(".zip", ".7z")
}

/** Mod preview images (`_metadata/0.png`), scaled down so a long list does not hold full-size pictures. */
object Previews {
    private const val MAX_ENTRIES = 400
    private val cache = object : LinkedHashMap<Pair<Path, Int>, ImageBitmap?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Path, Int>, ImageBitmap?>?) = size > MAX_ENTRIES
    }

    fun cached(path: Path, maxWidth: Int): ImageBitmap? = synchronized(cache) { cache[path to maxWidth] }

    /** Reads and scales the image; blocking, call it off the UI thread. Null when there is none or it is broken. */
    fun load(path: Path, maxWidth: Int): ImageBitmap? {
        val key = path to maxWidth
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val bitmap = try {
            if (path.isRegularFile()) scaled(Image.makeFromEncoded(path.readBytes()), maxWidth) else null
        } catch (e: Exception) {
            logger.debug(e) { "Cannot read preview $path" }
            null
        }
        synchronized(cache) { cache[key] = bitmap }
        return bitmap
    }

    fun clear() = synchronized(cache) { cache.clear() }

    private fun scaled(image: Image, maxWidth: Int): ImageBitmap {
        if (image.width <= maxWidth) return image.toComposeImageBitmap()
        val height = (image.height.toLong() * maxWidth / image.width).toInt().coerceAtLeast(1)
        Surface.makeRasterN32Premul(maxWidth, height).use { surface ->
            surface.canvas.drawImageRect(image, Rect.makeWH(maxWidth.toFloat(), height.toFloat()))
            return surface.makeImageSnapshot().toComposeImageBitmap()
        }
    }
}

enum class ThemeChoice(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** The app's own settings, in `app.properties` next to the sign-ins. */
class AppSettings(private val file: Path) {
    var theme: ThemeChoice = ThemeChoice.SYSTEM
        private set

    init {
        try {
            if (file.isRegularFile()) {
                val props = Properties().apply { file.inputStream().use { load(it) } }
                theme =
                    ThemeChoice.entries.firstOrNull { it.name.equals(props.getProperty("theme"), ignoreCase = true) } ?: ThemeChoice.SYSTEM
            }
        } catch (e: Exception) {
            logger.warn(e) { "Cannot read $file, using the defaults" }
        }
    }

    fun updateTheme(choice: ThemeChoice) {
        theme = choice
        try {
            file.parent.createDirectories()
            file.outputStream().use { Properties().apply { setProperty("theme", choice.name.lowercase()) }.store(it, "shunter app") }
        } catch (e: IOException) {
            logger.warn(e) { "Cannot save $file" }
        }
    }
}
