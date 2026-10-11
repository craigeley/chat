package com.craigeley.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.material3.Text
import com.craigeley.chat.Attachment
import com.craigeley.chat.ColorMode
import com.craigeley.chat.ui.theme.ChatColors
import com.craigeley.chat.ui.theme.ChatType

/** How far pinch/double-tap zoom may go. The decoded bitmap is capped at 1080px
 *  on its long edge (see [com.craigeley.chat.Attachments]), so past ~4× there's
 *  no detail left to reveal on this screen anyway. */
private const val MAX_SCALE = 4f

/**
 * Full-screen viewer for one image attachment, opened by tapping it in the
 * thread. Same loader (and caches) as the inline rendering, so it appears
 * instantly. Pinch to zoom, drag to pan while zoomed; a single tap — or Back —
 * closes it instantly. (No double-tap zoom: listening for one delays every
 * single tap ~300ms, which made dismissal feel broken.) Pure black
 * behind the image, no chrome: the LightOS look.
 */
@Composable
fun ImageViewerScreen(
    attachment: Attachment,
    loadImage: suspend (Attachment) -> ImageBitmap?,
    onClose: () -> Unit,
) {
    // True color for exactly as long as the viewer is up (vandamd's zero trick;
    // see ColorMode — a no-op without the one-time WRITE_SECURE_SETTINGS grant).
    // Backgrounding mid-view is handled by MainActivity's onStop/onStart.
    val context = LocalContext.current
    DisposableEffect(Unit) {
        ColorMode.acquire(context)
        // Normally already released mid-close (below); this catches the viewer
        // being disposed some other way (e.g. the whole thread closing).
        onDispose { ColorMode.release(context) }
    }

    // Close instantly. The grayscale restore is an async settings write, so the
    // thread can show in color for a frame or two before it lands — an accepted
    // trade for a dismissal that feels immediate (the old 190ms fade-to-black
    // hid the flip but read as an unresponsive tap).
    fun close() {
        ColorMode.release(context)
        onClose()
    }

    BackHandler { close() }

    val bitmap by produceState<ImageBitmap?>(initialValue = null, attachment.guid) {
        value = loadImage(attachment)
    }

    // The zoom/pan transform: the image is drawn scaled by [scale] about the
    // screen centre, then shifted by [offset] (screen pixels).
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var container by remember { mutableStateOf(IntSize.Zero) }

    // Keep the image from being dragged fully off-screen: the translation is
    // bounded by how much the scaled content overhangs the container. (Bounded
    // on the container, not the fitted image, so a letterboxed photo can be
    // panned edge-to-edge — simple and good enough at these sizes.)
    fun clamp(o: Offset, s: Float): Offset {
        val maxX = container.width * (s - 1f) / 2f
        val maxY = container.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ChatColors.background)
            .onSizeChanged { container = it }
            .pointerInput(Unit) {
                // onTap alone fires on finger-up; adding onDoubleTap would hold
                // every tap for the double-tap timeout first.
                detectTapGestures(onTap = { close() })
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    // Keep the content point under the fingers fixed while the
                    // scale changes, then apply the drag. (graphicsLayer scales
                    // about the centre then translates, so a content point q —
                    // relative to centre — lands at s·q + offset.)
                    val newOffset = (centroid - center) - (centroid - center - offset) * (newScale / scale) + pan
                    offset = clamp(newOffset, newScale)
                    scale = newScale
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = attachment.transferName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        } else {
            Text(text = "[Image]", style = ChatType.hint, color = ChatColors.onSurfaceDisabled)
        }
    }
}
