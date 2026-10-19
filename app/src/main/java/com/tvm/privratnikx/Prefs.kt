package com.tvm.privratnikx

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        Api.useStub = emulation
        AppLog.app = ctx.applicationContext ?: ctx
    }

    var phone: String
        get() = sp.getString("phone", "")!!
        set(v) = sp.edit().putString("phone", v).apply()
    var key: String
        get() = sp.getString("key", "")!!
        set(v) = sp.edit().putString("key", v).apply()

    /** Масштаб интерфейса, %. */
    var scale: Int
        get() = sp.getInt("scale", 100)
        set(v) = sp.edit().putInt("scale", v).apply()

    /** 0 — как в системе, 1 — тёмная, 2 — светлая. */
    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v).apply()

    /** "" — как в системе, иначе код языка. */
    var lang: String
        get() = sp.getString("lang", "")!!
        set(v) = sp.edit().putString("lang", v).apply()

    var closeAfterOpen: Boolean
        get() = sp.getBoolean("closeAfterOpen", false)
        set(v) = sp.edit().putBoolean("closeAfterOpen", v).apply()

    var showVideo: Boolean
        get() = sp.getBoolean("showVideo", false)
        set(v) = sp.edit().putBoolean("showVideo", v).apply()

    var emulation: Boolean
        get() = sp.getBoolean("emulation", false)
        set(v) {
            sp.edit().putBoolean("emulation", v).apply()
            Api.useStub = v
        }

    var barriers: List<Barrier>
        get() {
            val arr = JSONArray(sp.getString("barriers", "[]"))
            return List(arr.length()) {
                val o = arr.getJSONObject(it)
                Barrier(o.getString("id"), o.getString("address"), o.getBoolean("enabled"))
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("id", it.id).put("address", it.address).put("enabled", it.enabled)) }
            sp.edit().putString("barriers", arr.toString()).apply()
        }

    /** Всё, что требует пересоздания Activity при изменении. */
    val uiSignature get() = "$scale|$theme|$lang"

    companion object {
        /** Язык, тема и масштаб — через Configuration, чтобы ресурсы (values-ru, values-night) подхватились сами. */
        fun wrap(base: Context): Context {
            val p = Prefs(base)
            val cfg = Configuration(base.resources.configuration)
            if (p.lang.isNotEmpty()) cfg.setLocale(Locale.forLanguageTag(p.lang))
            if (p.theme != 0) {
                val night = if (p.theme == 1) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            }
            cfg.densityDpi = cfg.densityDpi * p.scale / 100
            return base.createConfigurationContext(cfg)
        }
    }
}

/**
 * Лог: logcat, память (экран «Лог») и файл /Android/data/<пакет>/files/гггг_мм_дд.txt.
 * Ключ, токены и сессии маскируются: на Android 9 эту папку могут читать другие приложения.
 */
object AppLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyy_MM_dd", Locale.US)
    @Volatile var app: Context? = null
    private val dir by lazy { app?.getExternalFilesDir(null) }

    private val secrets = listOf(
        Regex("""\b([0-9a-fA-F]{4})[0-9a-fA-F]{28}\b""") to "$1…", // ключ
        Regex(""""(token|session)":"[^"]+"""") to "\"$1\":\"***\"", // в JSON
        Regex("""token=[^&\s"]+""") to "token=***", // в ссылке
    )

    fun mask(s: String) = secrets.fold(s) { acc, (re, to) -> re.replace(acc, to) }

    @Synchronized
    fun d(msg: String, t: Throwable? = null) {
        val now = Date()
        val line = "${fmt.format(now)} ${mask(msg)}" + (t?.let { "\n  ${mask(it.toString())}" } ?: "")
        Log.d("PrivratnikX", line)
        lines.addLast(line)
        if (lines.size > 500) lines.removeFirst()
        // ponytail: запись в главном потоке — строк единицы в минуту; старые файлы не удаляются
        try {
            dir?.let { File(it, fileFmt.format(now) + ".txt").appendText(line + "\n") }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun text() = lines.joinToString("\n")
}
