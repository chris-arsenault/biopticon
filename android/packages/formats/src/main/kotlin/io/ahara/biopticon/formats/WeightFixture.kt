package io.ahara.biopticon.formats

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode

data class WeightField(val number: Int, val name: String, val unit: String, val scale: Int, val size: Int = 2) {
    val invalid: Long get() = if (size == 1) 255 else 65535
    fun encode(value: BigDecimal?): Long {
        if (value == null) return invalid
        val encoded = value.multiply(BigDecimal(scale)).setScale(0, RoundingMode.HALF_UP).longValueExact()
        val upper = if (name == "weight") invalid - 1 else if (name == "user_profile_index") 4096 else invalid
        require(encoded in 0 until upper) { "FIELD_OUT_OF_RANGE:$name" }
        return encoded
    }
}

/** Synthetic offline file for the weight-scale experiment; no import or network behavior. */
object WeightFixture {
    // FIT profile 21.218.0, weight_scale message 30. SDK decoder verifies these definitions.
    val fields = listOf(WeightField(0, "weight", "kg", 100), WeightField(1, "percent_fat", "%", 100),
        WeightField(2, "percent_hydration", "%", 100), WeightField(3, "visceral_fat_mass", "kg", 100),
        WeightField(4, "bone_mass", "kg", 100), WeightField(5, "muscle_mass", "kg", 100),
        WeightField(7, "basal_met", "kcal/day", 4), WeightField(8, "physique_rating", "index", 1, 1),
        WeightField(9, "active_met", "kcal/day", 4), WeightField(10, "metabolic_age", "years", 1, 1),
        WeightField(11, "visceral_fat_rating", "index", 1, 1), WeightField(12, "user_profile_index", "index", 1),
        WeightField(13, "bmi", "kg/m^2", 10))

    fun encode(values: Map<String, BigDecimal?>, bigEndian: Boolean = false): ByteArray {
        require(values.keys.all { key -> fields.any { it.name == key } }) { "UNKNOWN_FIELD" }
        val data = ByteArrayOutputStream()
        fun write(value: Long, size: Int, big: Boolean = bigEndian) {
            repeat(size) { i -> data.write((value ushr (8 * if (big) size - i - 1 else i)).toInt() and 255) }
        }
        fun definition(local: Int, global: Int, definitions: List<Triple<Int, Int, Int>>) {
            data.write(0x40 or local); data.write(0); data.write(if (bigEndian) 1 else 0)
            write(global.toLong(), 2); data.write(definitions.size)
            definitions.forEach { (id, size, type) -> data.write(id); data.write(size); data.write(type) }
        }
        definition(0, 0, listOf(Triple(0, 1, 0), Triple(1, 2, 132), Triple(4, 4, 134)))
        data.write(0); data.write(9); write(255, 2); write(1100000000, 4)
        val selected = fields.filter { it.name in values }
        definition(1, 30, listOf(Triple(253, 4, 134)) + selected.map { Triple(it.number, it.size, if (it.size == 1) 2 else 132) })
        data.write(1); write(1100000000, 4)
        selected.forEach { write(it.encode(values[it.name]), it.size) }
        val payload = data.toByteArray()
        val output = ByteArray(14 + payload.size + 2)
        output[0] = 14; output[1] = 0x20
        output[2] = (21218 and 255).toByte(); output[3] = (21218 ushr 8).toByte()
        repeat(4) { output[4 + it] = (payload.size ushr (8 * it)).toByte() }
        ".FIT".toByteArray(Charsets.US_ASCII).copyInto(output, 8)
        val headerCrc = Fit.crc(output, 12)
        output[12] = headerCrc.toByte(); output[13] = (headerCrc ushr 8).toByte()
        payload.copyInto(output, 14)
        val crc = Fit.crc(output, output.size - 2)
        output[output.size - 2] = crc.toByte(); output[output.size - 1] = (crc ushr 8).toByte()
        return output
    }
}
