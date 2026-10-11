package io.ahara.biopticon.formats

import com.garmin.fit.Decode
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.util.Random

class FormatCasesTest {
    @Test fun weightFieldCombinationsDecodeIndependentlyInBothByteOrders() {
        repeat(32) { index ->
            val selected = WeightFixture.fields.filterIndexed { i, _ -> (index shr (i % 5)) and 1 != 0 }
            val values = selected.associate { it.name to if (index % 7 == 0) null else BigDecimal("12.34") }
            val bytes = WeightFixture.encode(values, index % 2 == 1)
            val shape = Fit.inspect(bytes)
            assertTrue("case $index", shape.crcValid && 30 in shape.globalMessages)
            assertTrue("case $index", Decode().checkFileIntegrity(bytes.inputStream()))
            assertTrue("case $index", Decode().read(bytes.inputStream()))
        }
    }

    @Test fun seededCsvDialectCasesPreserveQuotedCells() {
        val random = Random(20261010L)
        repeat(48) { index ->
            val delimiter = listOf(',', ';', '\t')[index % 3]
            val columns = 1 + random.nextInt(8)
            val source = (0 until columns).joinToString(delimiter.toString()) { "field_$it" } + "\n" +
                (0 until columns).joinToString(delimiter.toString()) { if (index % 4 == 0) "\"quoted${delimiter}cell\"" else "synthetic" } + "\n"
            val shape = Csv.inspect(source)
            assertEquals("case $index", columns, shape.headers.size)
            assertEquals("case $index", 1, shape.rowCount)
            assertEquals("case $index", setOf(columns), shape.widths)
        }
    }

    @Test fun seededMalformedFitCasesAreRejected() {
        val random = Random(20261010L)
        repeat(32) { index ->
            val bytes = ByteArray(14 + random.nextInt(64)).apply { random.nextBytes(this) }
            assertThrows("case $index", IllegalArgumentException::class.java) { Fit.inspect(bytes) }
        }
    }
}
