package com.smartcreations.ledmatrixauto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wraps 16-bit mono [pcm] samples in a WAV header. */
fun wav(pcm: ByteArray, sampleRate: Int): ByteArray =
    ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1.toShort()); putShort(1.toShort())
        putInt(sampleRate); putInt(sampleRate * 2); putShort(2.toShort()); putShort(16.toShort())
        put("data".toByteArray()); putInt(pcm.size)
        put(pcm)
    }.array()
