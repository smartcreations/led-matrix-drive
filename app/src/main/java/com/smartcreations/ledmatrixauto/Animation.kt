package com.smartcreations.ledmatrixauto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/** An LED animation: [frames] of [width] x [height] ARGB pixels, row by row. */
class Animation(
    val title: String,
    val prompt: String,
    val fps: Int,
    val width: Int,
    val height: Int,
    val frames: List<IntArray>,
) {
    /** The frame to show [elapsedMs] after the animation started, looping. */
    fun frameIndexAt(elapsedMs: Long): Int = ((elapsedMs * fps / 1000) % frames.size).toInt()

    companion object {
        /**
         * Reads the server's upload format (led-matrix-ai lib/lmx.ts): "LMX1", width, height, fps,
         * frame count, pixel byte length, then RGB565 pixels. Inflates it first if it came zlib packed.
         */
        fun fromLmx(body: ByteArray, title: String, prompt: String): Animation {
            val raw = if (body.size >= 4 && String(body, 0, 4, Charsets.US_ASCII) == "LMX1") body else inflate(body)
            require(raw.size >= 16 && String(raw, 0, 4, Charsets.US_ASCII) == "LMX1") { "Not an LED animation" }
            val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val width = raw[4].toInt() and 0xff
            val height = raw[5].toInt() and 0xff
            val fps = (raw[6].toInt() and 0xff).coerceAtLeast(1)
            val count = buf.getShort(8).toInt() and 0xffff
            require(count > 0 && raw.size >= 16 + count * width * height * 2) { "Animation is cut short" }
            buf.position(16)
            val frames = List(count) {
                IntArray(width * height) {
                    val c = buf.short.toInt() and 0xffff
                    if (c == 0) 0 else {
                        val r = (c shr 11) and 0x1f
                        val g = (c shr 5) and 0x3f
                        val b = c and 0x1f
                        (0xff shl 24) or ((r * 255 / 31) shl 16) or ((g * 255 / 63) shl 8) or (b * 255 / 31)
                    }
                }
            }
            return Animation(title, prompt, fps, width, height, frames)
        }

        private fun inflate(data: ByteArray): ByteArray {
            val inflater = Inflater()
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 8)
            val chunk = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(chunk)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(chunk, 0, n)
            }
            inflater.end()
            return out.toByteArray()
        }
    }
}
