package com.tvm.privratnikx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Barrier(val id: String, val address: String, val enabled: Boolean = true)

/** Клиент api.privratnik.net. Все методы блокирующие — звать из фонового потока. */
object Api {
    private const val URL_API = "https://api.privratnik.net:44590/app/api.php"

    var useStub = false

    fun requestSms(phone: String): Boolean {
        if (useStub) return stub(true)
        return post(mapOf("number" to phone)).first == 200
    }

    fun requestKey(phone: String, sms: String): String {
        if (useStub) return stub("0123456789abcdef0123456789abcdef")
        return parseKey(post(mapOf("number" to phone, "smsCode" to sms)).second)
    }

    fun getBarriers(phone: String, key: String): List<Barrier> {
        if (useStub) return stub(listOf(Barrier("123", "Кремль, д. 1"), Barrier("456", "1600 Pennsylvania Ave")))
        return parseBarriers(post(mapOf("barrier" to "", "key" to key, "login" to phone)).second)
    }

    fun open(phone: String, key: String, id: String): Boolean {
        if (useStub) return stub(true)
        val body = mapOf("barrier_id" to id, "command" to "open", "key" to key, "login" to phone)
        return parseOpen(post(body, timeoutMs = 5_000).second)
    }

    /** Камеры шлагбаумов: barrier_id → camId. */
    fun getCameras(phone: String, key: String): Map<String, String> {
        if (useStub) return stub(mapOf("123" to "stub", "456" to "stub"))
        return parseCameras(post(mapOf("cam" to "", "key" to key, "login" to phone)).second)
    }

    /**
     * Текущий кадр камеры (JPEG 1280×720), декодированный в половинном разрешении.
     * Снимок почти живой (1–2 с), а HLS/MPEG-TS во встроенном плеере Android отстают на 30–45 с.
     */
    fun snapshot(phone: String, key: String, camId: String): Bitmap? {
        if (useStub) return stubSnapshot()
        val c = URL("$URL_API?login=$phone&key=$key&preview&camId=$camId").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5_000
            c.readTimeout = 5_000
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            return c.inputStream.use { BitmapFactory.decodeStream(it, null, opts) }
        } finally {
            c.disconnect()
        }
    }

    /** Кадр-заглушка для режима эмуляции: текущее время, чтобы было видно обновление. */
    private fun stubSnapshot(): Bitmap {
        val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.DKGRAY)
        val paint = Paint().apply { color = Color.WHITE; textSize = 64f; textAlign = Paint.Align.CENTER }
        canvas.drawText(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()), 320f, 200f, paint)
        return bmp
    }

    /** Ответ — массив массивов камер: [[{"id","camId","domain","token","barrier_id",…}]]. */
    fun parseCameras(json: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val outer = JSONArray(json)
        for (i in 0 until outer.length()) {
            val inner = outer.optJSONArray(i) ?: continue
            for (j in 0 until inner.length()) {
                val c = inner.getJSONObject(j)
                val barrierId = c.optString("barrier_id")
                if (barrierId !in result) result[barrierId] = c.optString("camId")
            }
        }
        return result
    }

    fun parseKey(json: String) = JSONObject(json).optString("key").trim()

    fun parseBarriers(json: String): List<Barrier> {
        val arr = JSONArray(json)
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            Barrier(o.optString("id"), o.optString("address"))
        }
    }

    fun parseOpen(json: String) = JSONObject(json).optInt("state") == 1

    fun isPhoneValid(s: String) = s.length == 11 && s[0] == '7' && s.all { it in '0'..'9' }
    fun isSmsValid(s: String) = s.length == 5 && s.all { it in '0'..'9' }
    fun isKeyValid(s: String) = s.length == 32 && s.all { it.lowercaseChar() in "0123456789abcdef" }

    private fun <T> stub(result: T): T {
        Thread.sleep(1000)
        return result
    }

    /** multipart/form-data POST, как MultipartFormDataContent в C#. Возвращает (код, тело). */
    private fun post(fields: Map<String, String>, timeoutMs: Int = 15_000): Pair<Int, String> {
        val boundary = UUID.randomUUID().toString()
        val body = buildString {
            for ((k, v) in fields) {
                append("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
            }
            append("--$boundary--\r\n")
        }.toByteArray()

        val c = URL(URL_API).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            AppLog.d("POST ${fields.keys} -> $code $text")
            return code to text
        } finally {
            c.disconnect()
        }
    }
}
