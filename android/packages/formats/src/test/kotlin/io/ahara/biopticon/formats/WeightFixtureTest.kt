package io.ahara.biopticon.formats

import com.garmin.fit.Decode
import com.garmin.fit.MesgListener
import com.garmin.fit.WeightScaleMesg
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class WeightFixtureTest {
    @Test fun independentlyDecodeEveryProfileFieldBothArchitectures() {
        for (big in listOf(false, true)) {
            val values = WeightFixture.fields.associate { it.name to BigDecimal("12") }
            val bytes = WeightFixture.encode(values, big)
            assertTrue(Fit.inspect(bytes).crcValid)
            assertTrue(Decode().checkFileIntegrity(bytes.inputStream()))
            val decoded = mutableListOf<WeightScaleMesg>()
            val decoder = Decode()
            decoder.addListener(MesgListener { if (it.num == 30) decoded.add(WeightScaleMesg(it)) })
            assertTrue(decoder.read(bytes.inputStream()))
            assertEquals(1, decoded.size)
            val result = decoded.single()
            WeightFixture.fields.forEach { field -> assertEquals(field.name, 12.0, result.getFieldFloatValue(field.number).toDouble(), 0.0001) }
            assertEquals(1100000000L, result.timestamp.timestamp)
        }
    }
    @Test fun unitsPrecisionAndInvalidSentinelsHaveExplicitBoundaries() {
        val weight = WeightFixture.fields.first()
        assertEquals(7000L, weight.encode(BigDecimal("70")))
        assertEquals(1235L, weight.encode(BigDecimal("12.345")))
        assertEquals(65535L, weight.encode(null))
        assertEquals(0L, weight.encode(BigDecimal.ZERO))
        for (value in listOf("-1", "655.34", "655.35", "999999999999999999999")) {
            assertThrows(RuntimeException::class.java) { weight.encode(BigDecimal(value)) }
        }
        val bytes = WeightFixture.encode(mapOf("weight" to null, "percent_fat" to BigDecimal("20")))
        var decoded: WeightScaleMesg? = null
        Decode().apply { addListener(MesgListener { if (it.num == 30) decoded = WeightScaleMesg(it) }) }.read(bytes.inputStream())
        assertNull(decoded!!.weight)
        assertEquals(20f, decoded!!.percentFat)
        assertNull(decoded!!.boneMass)
    }
    @Test fun corruptionAndTruncationDoNotPassIntegrity() {
        val bytes = WeightFixture.encode(mapOf("weight" to BigDecimal("70")))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertFalse(Fit.inspect(bytes).crcValid)
        assertFalse(Decode().checkFileIntegrity(bytes.inputStream()))
        assertThrows(IllegalArgumentException::class.java) { Fit.inspect(bytes.copyOf(bytes.size - 1)) }
    }
}
