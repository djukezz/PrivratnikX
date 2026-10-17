package com.tvm.privratnikx

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        Api.useStub = emulation
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

/** Лог в памяти для экрана «Лог» + logcat. */
object AppLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun d(msg: String, t: Throwable? = null) {
        Log.d("PrivratnikX", msg, t)
        lines.addLast("${fmt.format(Date())} $msg" + (t?.let { "\n  $it" } ?: ""))
        if (lines.size > 500) lines.removeFirst()
    }

    @Synchronized
    fun text() = lines.joinToString("\n")
}
