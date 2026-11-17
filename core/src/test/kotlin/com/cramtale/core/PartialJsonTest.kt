package com.cramtale.core

import com.cramtale.core.llm.PartialJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PartialJsonTest {
    @Test
    fun `the passage is readable while the JSON is still arriving`() {
        assertNull(PartialJson.stringField("{\"pass", "passage"))
        assertEquals("", PartialJson.stringField("{\"passage\": \"", "passage"))
        assertEquals("佐伯は", PartialJson.stringField("{\"passage\":\"佐伯は", "passage"))
        assertEquals("「鍵は", PartialJson.stringField("{\"passage\":\"「鍵は\\", "passage"))
        assertEquals("a\n「b」\"c\"", PartialJson.stringField("{\"passage\":\"a\\n「b」\\\"c\\\"\",\"used\":[", "passage"))
        assertEquals("é", PartialJson.stringField("{\"passage\":\"\\u00e9", "passage"))
        assertEquals("", PartialJson.stringField("{\"passage\":\"\\u00", "passage"))
    }
}
