package com.tvm.privratnikx

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import kotlin.concurrent.thread

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var phone: EditText
    private lateinit var sms: EditText
    private lateinit var key: EditText
    private lateinit var btnSms: Button
    private lateinit var btnKey: Button
    private lateinit var btnBarriers: Button
    private lateinit var barrierList: LinearLayout
    private lateinit var status: TextView
    private var statusColor = 0
    private var barriers = listOf<Barrier>()
    private var busy = false

    private val langs = arrayOf("", "ru", "en")

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Prefs.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)

        phone = findViewById(R.id.phone)
        sms = findViewById(R.id.sms)
        key = findViewById(R.id.key)
        btnSms = findViewById(R.id.requestSms)
        btnKey = findViewById(R.id.requestKey)
        btnBarriers = findViewById(R.id.requestBarriers)
        barrierList = findViewById(R.id.barrierList)
        status = findViewById(R.id.status)
        statusColor = status.currentTextColor

        // EditText сами восстанавливают текст после recreate(); из prefs — только при первом запуске
        if (savedInstanceState == null) {
            phone.setText(prefs.phone)
            key.setText(prefs.key)
        }
        showBarriers(prefs.barriers)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateButtons()
        }
        listOf(phone, sms, key).forEach { it.addTextChangedListener(watcher) }
        updateButtons()

        btnSms.setOnClickListener { requestSms() }
        btnKey.setOnClickListener { requestKey() }
        btnBarriers.setOnClickListener { requestBarriers() }

        // Язык, тема, масштаб: сохраняем сразу и пересоздаём экран, чтобы увидеть результат
        bindSpinner(R.id.lang, langs.indexOf(prefs.lang).coerceAtLeast(0)) { prefs.lang = langs[it] }
        bindSpinner(R.id.theme, prefs.theme) { prefs.theme = it }

        val scaleLabel = findViewById<TextView>(R.id.scaleLabel)
        val scale = findViewById<SeekBar>(R.id.scale)
        fun percent(progress: Int) = 20 + progress * 5
        scale.progress = (prefs.scale - 20) / 5
        scaleLabel.text = getString(R.string.scale, prefs.scale)
        scale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                scaleLabel.text = getString(R.string.scale, percent(progress))
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                if (percent(sb.progress) == prefs.scale) return
                prefs.scale = percent(sb.progress)
                recreate()
            }
        })

        findViewById<Switch>(R.id.closeAfterOpen).apply {
            isChecked = prefs.closeAfterOpen
            setOnCheckedChangeListener { _, v -> prefs.closeAfterOpen = v }
        }
        findViewById<Switch>(R.id.showVideo).apply {
            isChecked = prefs.showVideo
            setOnCheckedChangeListener { _, v -> prefs.showVideo = v }
        }
        findViewById<Switch>(R.id.emulation).apply {
            isChecked = prefs.emulation
            setOnCheckedChangeListener { _, v ->
                prefs.emulation = v
                AppLog.d("API emulation: $v")
            }
        }
        findViewById<Button>(R.id.log).setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.log).setMessage(AppLog.text())
                .setPositiveButton(android.R.string.ok, null).show()
        }
    }

    override fun onPause() {
        super.onPause()
        prefs.phone = phone.text.toString()
        prefs.key = key.text.toString()
        prefs.barriers = barriers.mapIndexed { i, b -> b.copy(enabled = (barrierList.getChildAt(i) as CheckBox).isChecked) }
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun bindSpinner(id: Int, selected: Int, onChange: (Int) -> Unit) {
        val spinner = findViewById<Spinner>(id)
        spinner.setSelection(selected)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, rowId: Long) {
                if (position == selected) return // первый вызов при раскладке
                onChange(position)
                recreate()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun showBarriers(list: List<Barrier>) {
        barriers = list
        barrierList.removeAllViews()
        for (b in list) {
            barrierList.addView(CheckBox(this).apply {
                text = b.address
                isChecked = b.enabled
                minHeight = (48 * resources.displayMetrics.density).toInt()
            })
        }
    }

    private fun updateButtons() {
        val phoneOk = !busy && Api.isPhoneValid(phone.text.toString())
        btnSms.isEnabled = phoneOk
        btnKey.isEnabled = phoneOk && Api.isSmsValid(sms.text.toString())
        btnBarriers.isEnabled = phoneOk && Api.isKeyValid(key.text.toString())
    }

    private fun setStatus(text: String, isError: Boolean = false) {
        status.text = text
        status.setTextColor(if (isError) Color.RED else statusColor)
    }

    /** Запускает запрос в фоне; [onResult] — в UI-потоке, null — исключение. */
    private fun <T> request(progress: Int, call: () -> T, onResult: (T?) -> Unit) {
        busy = true
        updateButtons()
        setStatus(getString(progress))
        thread {
            val result = try {
                call()
            } catch (e: Exception) {
                AppLog.d("Request failed", e)
                null
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                updateButtons()
                onResult(result)
            }
        }
    }

    private fun requestSms() {
        val p = phone.text.toString()
        AppLog.d("Requesting SMS for $p")
        request(R.string.requesting_sms, { Api.requestSms(p) }) { ok ->
            if (ok == true) setStatus(getString(R.string.input_sms))
            else setStatus(getString(R.string.sms_request_failed), true)
        }
    }

    private fun requestKey() {
        val p = phone.text.toString()
        val s = sms.text.toString()
        AppLog.d("Requesting key for $p, sms $s")
        request(R.string.requesting_key, { Api.requestKey(p, s) }) { k ->
            AppLog.d("Got key '$k'")
            if (k != null && Api.isKeyValid(k)) {
                key.setText(k)
                setStatus(getString(R.string.got_key))
            } else setStatus(getString(R.string.key_request_failed), true)
        }
    }

    private fun requestBarriers() {
        val p = phone.text.toString()
        val k = key.text.toString()
        AppLog.d("Requesting barriers for $p")
        request(R.string.requesting_barriers, { Api.getBarriers(p, k) }) { list ->
            if (list == null) {
                setStatus(getString(R.string.error), true)
                return@request
            }
            AppLog.d("Barriers: " + list.joinToString("; ") { "${it.address} [${it.id}]" })
            // сохраняем галочки у уже известных шлагбаумов
            val old = barriers.associate { it.id to it.enabled }
            showBarriers(list.map { it.copy(enabled = old[it.id] ?: true) })
            setStatus(getString(R.string.barriers_count, list.size))
        }
    }
}
