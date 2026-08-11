package com.localbabymonitor.app

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import kotlin.math.roundToInt

/** SurfaceView that always fits the decoded video without stretching it. */
class AspectRatioSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr) {

    private var videoWidth = 16
    private var videoHeight = 9

    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (videoWidth == width && videoHeight == height) return
        videoWidth = width
        videoHeight = height
        holder.setFixedSize(width, height)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthLimit = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightLimit = MeasureSpec.getSize(heightMeasureSpec)

        if (videoWidth <= 0 || videoHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val ratio = videoWidth.toFloat() / videoHeight.toFloat()
        val maxWidth = when (widthMode) {
            MeasureSpec.UNSPECIFIED -> videoWidth
            else -> widthLimit
        }.coerceAtLeast(1)
        val maxHeight = when (heightMode) {
            MeasureSpec.UNSPECIFIED -> videoHeight
            else -> heightLimit
        }.coerceAtLeast(1)

        var measuredWidth = maxWidth
        var measuredHeight = (measuredWidth / ratio).roundToInt()
        if (measuredHeight > maxHeight) {
            measuredHeight = maxHeight
            measuredWidth = (measuredHeight * ratio).roundToInt()
        }

        setMeasuredDimension(measuredWidth.coerceAtLeast(1), measuredHeight.coerceAtLeast(1))
    }
}
