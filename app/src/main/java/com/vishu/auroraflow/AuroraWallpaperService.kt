package com.vishu.auroraflow

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val NB = 8

private class Palette(
    val bg: Int,
    val h0: Float,
    val h1: Float,
    val s: Float,
    val v: Float
)

private val PALETTES = listOf(
    Palette(Color.rgb(6, 11, 20), 155f, 265f, 0.65f, 1f),
    Palette(Color.rgb(13, 9, 7), 6f, 48f, 0.75f, 1f),
    Palette(Color.rgb(11, 7, 18), 262f, 335f, 0.55f, 1f)
)

class AuroraWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = AuroraEngine()

    private inner class AuroraEngine : Engine(), Choreographer.FrameCallback {

        private val prefs = getSharedPreferences("aurora", Context.MODE_PRIVATE)
        private var pal = prefs.getInt("pal", 0).coerceIn(0, PALETTES.size - 1)
        private val density = resources.displayMetrics.density

        private var bmp: Bitmap? = null
        private var bc: Canvas? = null
        private var bw = 1f
        private var bh = 1f
        private var scaleF = 1f
        private var k = 1f

        private var visible = false
        private var last = 0L
        private var t = 0f
        private var scroll = 0f
        private var scrollTarget = 0f

        private var count = 0
        private var x = FloatArray(0)
        private var y = FloatArray(0)
        private var ox = FloatArray(0)
        private var oy = FloatArray(0)
        private var vx = FloatArray(0)
        private var vy = FloatArray(0)
        private var o = FloatArray(0)
        private var age = FloatArray(0)
        private var life = FloatArray(0)

        private val segs = Array(NB) { FloatArray(0) }
        private val segN = IntArray(NB)
        private val bucketColors = IntArray(NB)

        private val ptrX = FloatArray(5)
        private val ptrY = FloatArray(5)
        private var nPtr = 0
        private var pulseUntil = 0L
        private var lastTouch = 0L
        private var lastTap = 0L
        private var lastTapX = 0f
        private var lastTapY = 0f

        private val ex = FloatArray(6)
        private val ey = FloatArray(6)
        private val ek = FloatArray(6)

        private val ripX = FloatArray(8)
        private val ripY = FloatArray(8)
        private val ripR = FloatArray(8)
        private val ripA = FloatArray(8)
        private var ripIdx = 0

        private val fadePaint = Paint()
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }
        private val blitPaint = Paint().apply { isFilterBitmap = true }
        private val srcRect = Rect()
        private val dstRect = Rect()

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            buildColors()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            val s = max(1, ceil(max(width, height) / 1280.0).toInt())
            scaleF = s.toFloat()
            k = density / scaleF
            val w = max(2, width / s)
            val h = max(2, height / s)
            bmp?.recycle()
            val nb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(nb)
            canvas.drawColor(PALETTES[pal].bg)
            bmp = nb
            bc = canvas
            bw = w.toFloat()
            bh = h.toFloat()
            initParticles()
            nPtr = 0
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            Choreographer.getInstance().removeFrameCallback(this)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            visible = false
            Choreographer.getInstance().removeFrameCallback(this)
            bmp?.recycle()
            bmp = null
            bc = null
            super.onDestroy()
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            val ch = Choreographer.getInstance()
            ch.removeFrameCallback(this)
            if (isVisible) {
                last = 0L
                ch.postFrameCallback(this)
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int
        ) {
            scrollTarget = xOffset * 700f
        }

        override fun onTouchEvent(event: MotionEvent) {
            lastTouch = SystemClock.uptimeMillis()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tapAt(event.x, event.y, false)
                    fill(event, -1)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = event.actionIndex
                    addRipple(event.getX(i) / scaleF, event.getY(i) / scaleF)
                    fill(event, -1)
                }
                MotionEvent.ACTION_MOVE -> fill(event, -1)
                MotionEvent.ACTION_POINTER_UP -> fill(event, event.actionIndex)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> nPtr = 0
            }
        }

        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean
        ): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP &&
                SystemClock.uptimeMillis() - lastTouch > 600L
            ) {
                tapAt(x.toFloat(), y.toFloat(), true)
            }
            return null
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!visible) return
            Choreographer.getInstance().postFrameCallback(this)
            val now = frameTimeNanos / 1_000_000L
            val dt = if (last == 0L) 1f else min(32L, now - last) / 16.67f
            last = now
            draw(dt)
            blit()
        }

        private fun fill(e: MotionEvent, skip: Int) {
            var n = 0
            for (i in 0 until min(e.pointerCount, 5)) {
                if (i == skip) continue
                ptrX[n] = e.getX(i) / scaleF
                ptrY[n] = e.getY(i) / scaleF
                n++
            }
            nPtr = n
            pulseUntil = 0L
        }

        private fun tapAt(sx: Float, sy: Float, fromCommand: Boolean) {
            val now = SystemClock.uptimeMillis()
            val bx = sx / scaleF
            val by = sy / scaleF
            addRipple(bx, by)
            val dist = hypot((sx - lastTapX).toDouble(), (sy - lastTapY).toDouble())
            if (now - lastTap < 350L && dist < 150.0) {
                nextPalette()
                lastTap = 0L
            } else {
                lastTap = now
                lastTapX = sx
                lastTapY = sy
            }
            if (fromCommand) {
                ptrX[0] = bx
                ptrY[0] = by
                nPtr = 1
                pulseUntil = now + 600L
            }
        }

        private fun addRipple(bx: Float, by: Float) {
            ripX[ripIdx] = bx
            ripY[ripIdx] = by
            ripR[ripIdx] = 4f * k
            ripA[ripIdx] = 1f
            ripIdx = (ripIdx + 1) % 8
        }

        private fun nextPalette() {
            pal = (pal + 1) % PALETTES.size
            prefs.edit().putInt("pal", pal).apply()
            buildColors()
            val bg = PALETTES[pal].bg
            bc?.drawColor(Color.argb(170, Color.red(bg), Color.green(bg), Color.blue(bg)))
        }

        private fun buildColors() {
            val p = PALETTES[pal]
            for (b in 0 until NB) {
                val hue = p.h0 + (p.h1 - p.h0) * (b + 0.5f) / NB
                bucketColors[b] = Color.HSVToColor(153, floatArrayOf(hue, p.s, p.v))
            }
        }

        private fun initParticles() {
            count = ((bw * bh) / 700f).toInt().coerceIn(450, 1100)
            x = FloatArray(count)
            y = FloatArray(count)
            ox = FloatArray(count)
            oy = FloatArray(count)
            vx = FloatArray(count)
            vy = FloatArray(count)
            o = FloatArray(count)
            age = FloatArray(count)
            life = FloatArray(count)
            for (i in 0 until count) respawn(i)
            for (b in 0 until NB) {
                segs[b] = FloatArray(count * 4)
                segN[b] = 0
            }
        }

        private fun respawn(i: Int) {
            x[i] = Random.nextFloat() * bw
            y[i] = Random.nextFloat() * bh
            ox[i] = x[i]
            oy[i] = y[i]
            vx[i] = 0f
            vy[i] = 0f
            o[i] = Random.nextFloat()
            age[i] = 0f
            life[i] = 200f + Random.nextFloat() * 400f
        }

        private fun ang(cx: Float, cy: Float): Float {
            val s = 0.0028f
            val ax = cx + scroll
            return (sin(ax * s * 1.3f + t * 0.35f) +
                cos(cy * s * 1.7f - t * 0.28f) +
                sin((ax + cy) * s * 0.9f + t * 0.2f) * 0.8f) * 1.6f
        }

        private fun draw(dt: Float) {
            val c = bc ?: return
            if (count == 0) return

            val nowMs = SystemClock.uptimeMillis()
            if (pulseUntil != 0L && nowMs > pulseUntil) {
                nPtr = 0
                pulseUntil = 0L
            }

            t += 0.016f * dt
            scroll += (scrollTarget - scroll) * min(1f, 0.08f * dt)

            val p = PALETTES[pal]
            fadePaint.color = Color.argb(30, Color.red(p.bg), Color.green(p.bg), Color.blue(p.bg))
            c.drawRect(0f, 0f, bw, bh, fadePaint)

            val pc: Int
            if (nPtr > 0) {
                for (j in 0 until nPtr) {
                    ex[j] = ptrX[j]
                    ey[j] = ptrY[j]
                    ek[j] = 1f
                }
                pc = nPtr
            } else {
                ex[0] = bw * (0.5f + 0.32f * sin(t * 0.45f))
                ey[0] = bh * (0.55f + 0.28f * sin(t * 0.31f + 1f))
                ek[0] = 0.45f
                pc = 1
            }

            val r = min(bw, bh) * 0.28f
            val r2 = r * r
            val edge = 10f * k

            for (i in 0 until count) {
                ox[i] = x[i]
                oy[i] = y[i]
                val a = ang(x[i] / k, y[i] / k)
                vx[i] += cos(a) * 0.09f * dt
                vy[i] += sin(a) * 0.09f * dt

                for (j in 0 until pc) {
                    val dx = ex[j] - x[i]
                    val dy = ey[j] - y[i]
                    val d2 = dx * dx + dy * dy
                    if (d2 < r2 && d2 > 1f) {
                        val d = sqrt(d2)
                        val f = (1f - d / r) * ek[j]
                        val inv = 1f / d
                        val pull = if (d < r * 0.14f) -0.8f else 0.35f
                        vx[i] += (-dy * inv * 0.9f + dx * inv * pull) * f * dt
                        vy[i] += (dx * inv * 0.9f + dy * inv * pull) * f * dt
                    }
                }

                vx[i] *= 0.92f
                vy[i] *= 0.92f
                x[i] += vx[i] * dt * 1.6f * k
                y[i] += vy[i] * dt * 1.6f * k
                age[i] += dt

                if (x[i] < -edge || x[i] > bw + edge || y[i] < -edge || y[i] > bh + edge || age[i] > life[i]) {
                    respawn(i)
                    continue
                }

                val m = o[i] * 0.6f + 0.4f * (0.5f + 0.5f * sin(x[i] / k * 0.003f + t * 0.2f))
                val b = min(NB - 1, (m * NB).toInt())
                val s = segs[b]
                val n = segN[b]
                s[n] = ox[i]
                s[n + 1] = oy[i]
                s[n + 2] = x[i]
                s[n + 3] = y[i]
                segN[b] = n + 4
            }

            linePaint.strokeWidth = 1.1f * k
            for (b in 0 until NB) {
                val n = segN[b]
                if (n == 0) continue
                linePaint.color = bucketColors[b]
                c.drawLines(segs[b], 0, n, linePaint)
                segN[b] = 0
            }

            ringPaint.strokeWidth = 1.4f * k
            val mid = (p.h0 + p.h1) / 2f
            for (q in 0 until 8) {
                val a = ripA[q]
                if (a <= 0f) continue
                ripR[q] += 3.2f * dt * k
                ripA[q] = a - 0.018f * dt
                val alpha = (a * 115f).toInt().coerceIn(0, 255)
                ringPaint.color = Color.HSVToColor(alpha, floatArrayOf(mid, p.s, p.v))
                c.drawCircle(ripX[q], ripY[q], ripR[q], ringPaint)
            }
        }

        private fun blit() {
            val b = bmp ?: return
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    srcRect.set(0, 0, b.width, b.height)
                    dstRect.set(0, 0, canvas.width, canvas.height)
                    canvas.drawBitmap(b, srcRect, dstRect, blitPaint)
                }
            } catch (e: Exception) {
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }
}
