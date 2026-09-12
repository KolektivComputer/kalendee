package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.env.LocalEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalEnvTest {
    @Test
    fun parseLineHandlesQuotesExportAndComments() {
        assertEquals(
            "KALENDEE_DATABASE_URL" to "jdbc:postgresql://localhost:5432/kalendee",
            LocalEnv.parseLine("""KALENDEE_DATABASE_URL="jdbc:postgresql://localhost:5432/kalendee""""),
        )
        assertEquals(
            "KALENDEE_DATABASE_USER" to "kalendee",
            LocalEnv.parseLine("export KALENDEE_DATABASE_USER=kalendee"),
        )
        assertEquals(
            "KALENDEE_ADMIN_PASSWORD" to "adminpass1012",
            LocalEnv.parseLine("KALENDEE_ADMIN_PASSWORD=adminpass1012"),
        )
        assertNull(LocalEnv.parseLine("# comment"))
        assertNull(LocalEnv.parseLine("   "))
        assertNull(LocalEnv.parseLine("NOVALUE"))
    }
}
