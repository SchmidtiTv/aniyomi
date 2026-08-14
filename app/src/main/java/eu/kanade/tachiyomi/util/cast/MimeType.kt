package eu.kanade.tachiyomi.util.cast

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.core.net.toUri

fun Context.resolveMimeType(url: String): String? {
    if (url.startsWith("content://")) {
        contentResolver.getType(url.toUri())?.let { return it }
    }
    val ext = MimeTypeMap.getFileExtensionFromUrl(url).lowercase().ifEmpty { null }
    return ext?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
}
