package com.tvm.privratnikx

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var uiSignature: String
    private lateinit var status: TextView
    private lateinit var barriersView: LinearLayout
    private var statusColor = 0
    /** Меняется, когда экран уходит в фон или перестраивается: старые циклы снимков завершаются. */
    @Volatile private var generation = 0

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
    }

    // Не уничтожаем экран, а уводим в фон: следующий запуск будет горячим (~100 мс против ~260 мс)
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun buildButtons() {
        generation++
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

    /** Половина кнопки с камерой. Кнопка от неё не зависит: кадры грузятся в фоне, тап по картинке = тап по кнопке. */
    private fun cameraFor(b: Barrier, btn: Button): View {
        val image = ImageView(this)
        image.setBackgroundColor(Color.BLACK)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.setOnClickListener { btn.performClick() }
        startSnapshots(image, b)
        return image
    }

    /**
     * Снимок раз в секунду вместо видео: у снимка задержка 1–2 с, а у потока во встроенном плеере — 30–45 с.
     * ponytail: ~80 КБ на кадр (~5 МБ/мин, пока экран открыт); ExoPlayer + mpegts, если нужны настоящие 6 кадров/с.
     */
    private fun startSnapshots(image: ImageView, b: Barrier) {
        val gen = generation
        val phone = prefs.phone
        val key = prefs.key
        thread {
            var camId: String? = null
            while (gen == generation) {
                val started = SystemClock.elapsedRealtime()
                try {
                    if (camId == null) {
                        camId = Api.getCameras(phone, key)[b.id]
                        if (camId == null) return@thread AppLog.d("No camera for barrier ${b.id}")
                    }
                    val bmp = Api.snapshot(phone, key, camId)
                    runOnUiThread { if (gen == generation) image.setImageBitmap(bmp) }
                    Thread.sleep((1_000 - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0))
                } catch (e: Exception) {
                    AppLog.d("Snapshot ${b.id} failed", e)
                    Thread.sleep(3_000)
                }
            }
        }
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
