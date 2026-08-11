package com.overbit.babymonitor.ui

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.min

/**
 * A [SurfaceView] sized to the video's aspect ratio and rotated so the picture is upright.
 *
 * The camera encodes in sensor orientation, so the baby unit sends how far the picture has to
 * be turned rather than paying for a GPU rotation pass on the way in.
 *
 * @param fixBufferSize set for the baby unit's own preview, where the surface is a camera
 *   capture target and its buffer must be exactly one of the camera's supported sizes.
 */
@Composable
fun VideoSurface(
    width: Int,
    height: Int,
    rotationDegrees: Int,
    fixBufferSize: Boolean,
    onSurfaceChanged: (Surface?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (width <= 0 || height <= 0) {
        Box(modifier)
        return
    }
    val callback by rememberUpdatedState(onSurfaceChanged)
    val expectedWidth by rememberUpdatedState(width)
    val expectedHeight by rememberUpdatedState(height)

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val quarterTurned = rotationDegrees == 90 || rotationDegrees == 270
        val shownWidth = (if (quarterTurned) height else width).toFloat()
        val shownHeight = (if (quarterTurned) width else height).toFloat()
        val scale = min(maxWidth.value / shownWidth, maxHeight.value / shownHeight)
        // The view itself is laid out unrotated; rotating it lands it in the box above.
        val viewWidth = if (quarterTurned) shownHeight * scale else shownWidth * scale
        val viewHeight = if (quarterTurned) shownWidth * scale else shownHeight * scale

        AndroidView(
            modifier = Modifier
                .size(viewWidth.dp, viewHeight.dp)
                .graphicsLayer { rotationZ = rotationDegrees.toFloat() },
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            surfaceWidth: Int,
                            surfaceHeight: Int,
                        ) {
                            // Only hand the camera a surface once setFixedSize has taken hold,
                            // otherwise the capture session is configured for the wrong size.
                            val ready = !fixBufferSize ||
                                (surfaceWidth == expectedWidth && surfaceHeight == expectedHeight)
                            if (ready) callback(holder.surface)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) = callback(null)
                    })
                }
            },
            update = { view ->
                if (fixBufferSize) view.holder.setFixedSize(width, height)
            },
            onRelease = { callback(null) },
        )
    }
}
