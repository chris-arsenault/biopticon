package io.ahara.biopticon.formats

import org.junit.Assert.*
import org.junit.Test

class FormatsTest {
    @Test fun csvPreservesQuotedNewlinesEmptyFieldsAndEscapedQuotes() {
        val rows = Csv.parse("exercise,notes,load\r\n\"Press, seated\",\"one\n\"\"two\"\"\",\r\n", ',')
        assertEquals(listOf("Press, seated", "one\n\"two\"", ""), rows[1])
    }
    @Test fun dialectDiscoveryHandlesBomAndDecimalCommaWithoutLoggingValues() {
        val shape = Csv.inspect("\uFEFFexercise;load;reps\npress;12,5;8\n")
        assertEquals(';', shape.delimiter); assertEquals(1, shape.rowCount)
        assertEquals(listOf("exercise", "load", "reps"), shape.headers)
    }
    @Test fun malformedCsvIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { Csv.parse("a,\"unfinished", ',') }
    }
    @Test fun independentCrcReferenceAndLengthChecks() {
        assertEquals(0xbb3d, Fit.crc("123456789".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { Fit.inspect(ByteArray(14)) }
    }
}
