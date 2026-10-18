package com.tvm.privratnikx

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var uiSignature: String
    private lateinit var status: TextView
    private lateinit var barriersView: LinearLayout
    private var statusColor = 0
    /** Меняется, когда экран уходит в фон или перестраивается: старые циклы снимков завершаются. */
    @Volatile private var generation = 0
    private val players = mutableListOf<ExoPlayer>()

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Prefs.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        uiSignature = prefs.uiSignature
        status = findViewById(R.id.status)
        statusColor = status.currentTextColor
        barriersView = findViewById(R.id.barriers)
        findViewById<android.view.View>(R.id.settings).setOnClickListener { openSettings() }

        if (savedInstanceState == null && (prefs.key.isEmpty() || prefs.barriers.none { it.enabled }))
            openSettings()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.uiSignature != uiSignature) {
            recreate()
            return
        }
        buildButtons()
    }

    override fun onPause() {
        super.onPause()
        generation++
        players.forEach { it.release() }
        players.clear()
    }

    // Не уничтожаем экран, а уводим в фон: следующий запуск будет горячим (~100 мс против ~260 мс)
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun buildButtons() {
        generation++
        players.forEach { it.release() }
        players.clear()
        barriersView.removeAllViews()
        val barriers = prefs.barriers.filter { it.enabled }
        setStatus(if (barriers.isEmpty()) getString(R.string.no_barriers) else "", false)
        // Ячейки идут столбиком на всю ширину; делим ячейку пополам по длинной стороне
        val dm = resources.displayMetrics
        val wide = barriers.isNotEmpty() && dm.widthPixels > dm.heightPixels / barriers.size
        for (b in barriers) {
            val btn = Button(this)
            btn.text = b.address
            btn.isAllCaps = false
            fitWords(btn)
            val idleBg = btn.background
            btn.setOnClickListener { open(btn, b, idleBg) }
            val cell = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            if (!prefs.showVideo) {
                barriersView.addView(btn, cell)
                continue
            }
            val row = LinearLayout(this)
            row.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            val half = if (wide) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            row.addView(btn, half)
            row.addView(cameraFor(b, btn), LinearLayout.LayoutParams(half))
            barriersView.addView(row, cell)
        }
    }

    /**
     * Автоподбор размера текста, но не крупнее, чем влезает самое длинное слово:
     * иначе Android рвёт слова посередине («Крем-ль»).
     */
    private fun fitWords(btn: Button) {
        val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics).toInt()
        btn.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or_, _ ->
            if (r - l == or_ - ol) return@addOnLayoutChangeListener
            val width = (r - l - v.paddingLeft - v.paddingRight).toFloat()
            val paint = android.graphics.Paint(btn.paint).apply { textSize = 100f }
            val longest = btn.text.split(' ').maxOf { paint.measureText(it) }
            val maxPx = (100f * width / longest).toInt().coerceIn(minPx + 1, minPx * 13)
            btn.post { btn.setAutoSizeTextTypeUniformWithConfiguration(minPx, maxPx, 1, TypedValue.COMPLEX_UNIT_PX) }
        }
    }

    /** Половина кнопки с камерой. Кнопка от неё не зависит: камера грузится в фоне, тап по ней = тап по кнопке. */
    private fun cameraFor(b: Barrier, btn: Button): View {
        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        frame.setOnClickListener { btn.performClick() }
        val image = ImageView(this)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        frame.addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        startCamera(frame, image, b)
        return frame
    }

    /**
     * Снимок раз в секунду; если есть живой поток — параллельно запускаем его, и когда он пошёл
     * (это до ~8 с: ждём опорный кадр), убираем снимок. Без потока (режим эмуляции) — только снимки.
     */
    private fun startCamera(frame: FrameLayout, image: ImageView, b: Barrier) {
        val gen = generation
        val phone = prefs.phone
        val key = prefs.key
        val streaming = AtomicBoolean() // поток пошёл — снимки больше не нужны
        thread {
            var cam: Camera? = null
            while (gen == generation && !streaming.get()) {
                val started = SystemClock.elapsedRealtime()
                try {
                    if (cam == null) {
                        cam = Api.getCameras(phone, key)[b.id] ?: return@thread AppLog.d("No camera for barrier ${b.id}")
                        val stream = cam.stream
                        if (stream != null) runOnUiThread { if (gen == generation) startPlayer(frame, image, streaming, stream, gen) }
                    }
                    val bmp = Api.snapshot(phone, key, cam.camId)
                    runOnUiThread { if (gen == generation && !streaming.get()) image.setImageBitmap(bmp) }
                    Thread.sleep((1_000 - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0))
                } catch (e: Exception) {
                    AppLog.d("Camera ${b.id} failed", e)
                    Thread.sleep(3_000)
                }
            }
        }
    }

    /**
     * Живой MPEG-TS через ExoPlayer с маленьким буфером: задержка ~3 с вместо 30–45 с у встроенного плеера.
     * Всё скачанное сверх текущей позиции — это отставание от реального времени; если оно выросло
     * (сеть подвисла), догоняем ускоренным воспроизведением.
     */
    private fun startPlayer(frame: FrameLayout, image: ImageView, streaming: AtomicBoolean, url: String, gen: Int) {
        val created = SystemClock.elapsedRealtime()
        val surface = SurfaceView(this)
        // Под снимком: SurfaceView рисуется под окном, непрозрачная картинка его закрывает, пока видео не пошло
        frame.addView(surface, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        val player = ExoPlayer.Builder(this)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1_000, 3_000, 500, 1_000).build())
            .build()
        players.add(player)
        val source = ProgressiveMediaSource.Factory(DefaultHttpDataSource.Factory())
            .createMediaSource(MediaItem.fromUri(url))
        var playing = false
        fun reconnect(why: String) {
            if (gen != generation) return
            AppLog.d("Stream reconnect: $why")
            playing = false
            player.setMediaSource(source)
            player.prepare()
        }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> if (!playing) {
                        playing = true
                        streaming.set(true)
                        image.visibility = View.GONE
                        AppLog.d("Stream started in ${SystemClock.elapsedRealtime() - created} ms")
                    }
                    Player.STATE_ENDED -> reconnect("ended")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                AppLog.d("Stream error: ${error.errorCodeName}", error)
                frame.postDelayed({ reconnect("error") }, 3_000)
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width == 0 || size.height == 0) return
                val scale = minOf(frame.width / size.width.toFloat(), frame.height / size.height.toFloat())
                surface.layoutParams = FrameLayout.LayoutParams((size.width * scale).toInt(), (size.height * scale).toInt(), Gravity.CENTER)
            }
        })
        val catchUp = object : Runnable {
            override fun run() {
                if (gen != generation) return
                val lag = player.bufferedPosition - player.currentPosition
                val speed = if (lag > 1_500) 1.5f else if (lag < 700) 1f else player.playbackParameters.speed
                if (speed != player.playbackParameters.speed) {
                    AppLog.d("Stream lag $lag ms, speed $speed")
                    player.setPlaybackSpeed(speed)
                }
                frame.postDelayed(this, 1_000)
            }
        }
        frame.postDelayed(catchUp, 1_000)
        player.setVideoSurfaceView(surface)
        player.setMediaSource(source)
        player.playWhenReady = true
        player.prepare()
        AppLog.d("Player created in ${SystemClock.elapsedRealtime() - created} ms")
    }

    /** Красим копию стандартного фона: backgroundTintList не вернуть к исходному цвету кнопки. */
    private fun Button.paint(idleBg: Drawable, color: Int) {
        background = idleBg.constantState!!.newDrawable(resources, theme).mutate().apply { setTint(color) }
    }

    private fun open(btn: Button, b: Barrier, idleBg: Drawable) {
        btn.removeCallbacks(btn.tag as? Runnable) // отменяем сброс цвета от прошлого нажатия
        val phone = prefs.phone
        val key = prefs.key
        btn.isEnabled = false
        btn.paint(idleBg, 0xFFFFD700.toInt()) // gold
        setStatus(getString(R.string.opening), false)
        AppLog.d("Opening barrier ${b.id} (${b.address})")

        thread {
            val ok = try {
                Api.open(phone, key, b.id)
            } catch (e: Exception) {
                AppLog.d("Open failed", e)
                false
            }
            AppLog.d("Open barrier ${b.id}: $ok")
            runOnUiThread {
                btn.isEnabled = true
                btn.paint(idleBg, if (ok) 0xFF228B22.toInt() else 0xFFB22222.toInt())
                setStatus(getString(if (ok) R.string.opened else R.string.failed_to_open), !ok)
                val reset = Runnable {
                    btn.background = idleBg
                    if (ok && prefs.closeAfterOpen) moveTaskToBack(true)
                }
                btn.tag = reset
                btn.postDelayed(reset, 3_000)
            }
        }
    }

    private fun setStatus(text: String, isError: Boolean) {
        status.text = text
        status.setTextColor(if (isError) Color.RED else statusColor)
    }
}
