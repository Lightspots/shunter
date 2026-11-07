package ch.lightspots.shunter.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import ch.lightspots.shunter.app.Previews
import ch.lightspots.shunter.core.net.ImageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Where pictures from the sites ended up, so rows scrolled back into view show them at once. */
private val remoteFiles = ConcurrentHashMap<String, Path>()

/** The picture in [path] (a mod's `_metadata/0.png`), scaled to [maxWidth]; null while loading or when there is none. */
@Composable
fun rememberLocalImage(path: Path?, maxWidth: Int): ImageBitmap? {
    // Keyed by the path: another mod must not show the previous mod's picture
    val state = remember(path, maxWidth) { mutableStateOf(path?.let { Previews.cached(it, maxWidth) }) }
    LaunchedEffect(path, maxWidth) {
        if (path != null && state.value == null) state.value = withContext(Dispatchers.IO) { Previews.load(path, maxWidth) }
    }
    return state.value
}

/** The picture at [url] on a mod site, fetched through [cache]; null while loading or when it cannot be had. */
@Composable
fun rememberRemoteImage(url: String?, cache: ImageCache, maxWidth: Int): ImageBitmap? {
    val state = remember(url, maxWidth) { mutableStateOf(url?.let { remoteFiles[it] }?.let { Previews.cached(it, maxWidth) }) }
    LaunchedEffect(url, maxWidth) {
        if (url == null || state.value != null) return@LaunchedEffect
        val file = cache.get(url) ?: return@LaunchedEffect
        remoteFiles[url] = file
        state.value = withContext(Dispatchers.IO) { Previews.load(file, maxWidth) }
    }
    return state.value
}
