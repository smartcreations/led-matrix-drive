package com.smartcreations.ledmatrixauto

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import org.json.JSONObject

/** The same Vercel endpoints the LED screens use: an idea (typed or spoken) in, finished multicolor frames out. */
object Server {
    /** How the last request went, for the debug screen. */
    @Volatile var lastCall = "none yet"
        private set

    fun animation(prompt: String): Animation {
        val body = JSONObject().put("prompt", prompt).put("mode", if (Settings.tint == null) "rgb" else "mono").toString().toByteArray()
        return post("animation", "application/json", body) { prompt }
    }

    /** Sends a spoken idea as a WAV: the server writes down what was said and draws it. */
    fun voice(wav: ByteArray): Animation = post("voice", "audio/wav", wav) { conn ->
        conn.getHeaderField("X-Prompt")?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
    }

    /** POSTs [body] to /api/device/[path]; [promptOf] reads the idea the animation was drawn from. */
    private fun post(path: String, contentType: String, body: ByteArray, promptOf: (HttpURLConnection) -> String): Animation {
        EventLog.add("Server: asking $path")
        val conn = URL("${BuildConfig.SERVER_URL}/api/device/$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.DEVICE_KEY}")
            conn.setRequestProperty("X-Accept-Compression", "zlib")
            if (Settings.fine) conn.setRequestProperty("X-Display-Size", "128x64")
            conn.outputStream.use { it.write(body) }

            val status = conn.responseCode
            lastCall = "$path: $status"
            EventLog.add("Server: $path answered $status")
            if (status != 200) {
                val reason = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(120)
                throw IllegalStateException(if (reason.isNullOrBlank()) "Server said $status" else reason)
            }
            val prompt = promptOf(conn)
            val title = conn.getHeaderField("X-Animation-Title")?.let { URLDecoder.decode(it, "UTF-8") } ?: prompt
            val bytes = conn.inputStream.use { it.readBytes() }
            return Animation.fromLmx(bytes, title, prompt)
        } finally {
            conn.disconnect()
        }
    }
}
