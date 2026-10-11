package io.ahara.biopticon.formats

data class FitShape(val protocol: Int, val profile: Int, val dataBytes: Long, val crcValid: Boolean, val globalMessages: Set<Int>, val fields: Set<String>)

object Fit {
    private val table = intArrayOf(0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401, 0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400)
    fun crc(bytes: ByteArray, end: Int = bytes.size): Int {
        var crc = 0
        for (i in 0 until end) {
            val b = bytes[i].toInt() and 255
            var tmp = table[crc and 15]; crc = (crc ushr 4) and 0xfff; crc = crc xor tmp xor table[b and 15]
            tmp = table[crc and 15]; crc = (crc ushr 4) and 0xfff; crc = crc xor tmp xor table[(b ushr 4) and 15]
        }
        return crc
    }
    fun inspect(bytes: ByteArray): FitShape {
        require(bytes.size in 14..2_000_000) { "FIT_SIZE" }
        fun u8(at: Int) = bytes[at].toInt() and 255
        val header = u8(0)
        require(header in setOf(12, 14) && String(bytes, 8, 4, Charsets.US_ASCII) == ".FIT") { "FIT_HEADER" }
        val dataSize = (4..7).fold(0L) { total, i -> total or (u8(i).toLong() shl ((i - 4) * 8)) }
        require(header + dataSize + 2 == bytes.size.toLong()) { "FIT_LENGTH" }
        val end = header + dataSize.toInt()
        val definitions = mutableMapOf<Int, Pair<Int, Int>>()
        val globals = linkedSetOf<Int>(); val fields = linkedSetOf<String>()
        var offset = header
        while (offset < end) {
            val messageHeader = u8(offset++)
            val compressed = messageHeader and 0x80 != 0
            val local = if (compressed) (messageHeader ushr 5) and 3 else messageHeader and 15
            if (!compressed && messageHeader and 0x40 != 0) {
                require(offset + 5 <= end) { "FIT_DEFINITION" }
                val big = u8(offset + 1) != 0
                val global = if (big) (u8(offset + 2) shl 8) or u8(offset + 3) else u8(offset + 2) or (u8(offset + 3) shl 8)
                val count = u8(offset + 4); offset += 5
                var size = 0
                repeat(count) { require(offset + 3 <= end); fields.add("$global.${u8(offset)}:${u8(offset + 2)}"); size += u8(offset + 1); offset += 3 }
                if (messageHeader and 0x20 != 0) {
                    require(offset < end); val developers = u8(offset++)
                    repeat(developers) { require(offset + 3 <= end); fields.add("$global.developer.${u8(offset)}"); size += u8(offset + 1); offset += 3 }
                }
                definitions[local] = global to size; globals.add(global)
            } else {
                val definition = requireNotNull(definitions[local]) { "FIT_MISSING_DEFINITION" }
                val size = definition.second - if (compressed) 4 else 0
                require(size >= 0 && offset + size <= end) { "FIT_RECORD_LENGTH" }
                offset += size
            }
        }
        val expected = u8(end) or (u8(end + 1) shl 8)
        return FitShape(u8(1), u8(2) or (u8(3) shl 8), dataSize, crc(bytes, end) == expected, globals, fields)
    }
}
