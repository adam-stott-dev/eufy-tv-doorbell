package io.github.adamstottdev.eufytv.popup

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import io.github.adamstottdev.eufytv.R

/** The pop-up's content: live video, a "connecting" message and a label. */
@SuppressLint("ViewConstructor")
class PopupView(
    context: Context,
    label: String?,
    private val onSurface: (Surface) -> Unit,
    private val onClose: () -> Unit,
) : FrameLayout(context) {

    private val video = TextureView(context)
    private val message = TextView(context)
    private var videoW = 0
    private var videoH = 0

    init {
        val dp = resources.displayMetrics.density
        background = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = 12 * dp
        }
        clipToOutline = true
        isFocusable = true
        isFocusableInTouchMode = true

        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = onSurface(Surface(st))
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                if (message.visibility == VISIBLE) post { message.visibility = GONE }
            }
        }

        message.apply {
            setText(R.string.popup_connecting)
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        if (label != null) {
            val tag = TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
                background = GradientDrawable().apply {
                    setColor(0x99000000.toInt())
                    cornerRadius = 8 * dp
                }
            }
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
            lp.setMargins((12 * dp).toInt(), 0, 0, (12 * dp).toInt())
            addView(tag, lp)
        }
    }

    fun showMessage(text: String) {
        post {
            message.text = text
            message.visibility = VISIBLE
        }
    }

    /** Keeps the video's aspect ratio inside the pop-up. */
    fun setVideoSize(w: Int, h: Int) {
        post {
            videoW = w
            videoH = h
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (videoW > 0 && videoH > 0) {
            val boxW = measuredWidth
            val boxH = measuredHeight
            val scale = minOf(boxW.toFloat() / videoW, boxH.toFloat() / videoH)
            val lp = video.layoutParams
            val w = (videoW * scale).toInt()
            val h = (videoH * scale).toInt()
            if (lp.width != w || lp.height != h) {
                lp.width = w
                lp.height = h
                video.layoutParams = lp
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) onClose()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
