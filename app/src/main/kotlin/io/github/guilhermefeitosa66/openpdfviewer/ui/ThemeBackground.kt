package io.github.guilhermefeitosa66.openpdfviewer.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decoded theme images, kept so screens and bars share one bitmap per asset. */
object BackgroundCache {
    private val images = HashMap<String, ImageBitmap>()

    fun peek(asset: String): ImageBitmap? = synchronized(images) { images[asset] }

    suspend fun load(context: Context, asset: String, maxWidth: Int): ImageBitmap? {
        peek(asset)?.let { return it }
        val img = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.assets.open(asset).use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                context.assets.open(asset).use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
            }.getOrNull()
        } ?: return null
        synchronized(images) {
            // Only the full backgrounds are big: keep the latest one plus the small previews.
            if (!asset.endsWith("-thumb.webp")) images.keys.removeAll { !it.endsWith("-thumb.webp") }
            images[asset] = img
        }
        return img
    }
}

/**
 * The theme's background image, filling [modifier]. Loads after the first frame, off the main
 * thread, and fades in, so it never delays the app or a document from opening. Draws nothing when
 * the theme has no image or the user switched it off.
 */
@Composable
fun ThemeBackground(modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center) {
    val asset = LocalBackgroundAsset.current ?: return
    val context = LocalContext.current
    val width = context.resources.displayMetrics.widthPixels
    val image by produceState(BackgroundCache.peek(asset), asset) {
        if (value == null) {
            withFrameNanos { } // after the first frame
            value = BackgroundCache.load(context, asset, width)
        }
    }
    AnimatedVisibility(
        visible = image != null,
        enter = fadeIn(tween(250)),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        image?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, alignment = alignment, modifier = Modifier.fillMaxSize())
        }
    }
}
