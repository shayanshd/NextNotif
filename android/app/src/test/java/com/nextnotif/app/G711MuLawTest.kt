package com.nextnotif.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class G711MuLawTest {
    @Test
    fun `wire format halves pcm size and preserves speech polarity`() {
        val samples = shortArrayOf(-20_000, -4_000, 0, 4_000, 20_000)
        val pcm = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            pcm[index * 2] = sample.toByte()
            pcm[index * 2 + 1] = (sample.toInt() shr 8).toByte()
        }

        val encoded = G711MuLaw.encode(pcm)
        val decoded = G711MuLaw.decode(encoded)

        assertEquals(samples.size, encoded.size)
        samples.forEachIndexed { index, original ->
            val restored = ((decoded[index * 2].toInt() and 0xff) or
                (decoded[index * 2 + 1].toInt() shl 8)).toShort().toInt()
            if (original != 0.toShort()) assertTrue(restored * original > 0)
            assertTrue(abs(restored - original.toInt()) < 1_500)
        }
    }
}
