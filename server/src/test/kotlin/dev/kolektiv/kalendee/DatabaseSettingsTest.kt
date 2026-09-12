package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.db.parsePostgresUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DatabaseSettingsTest {
    @Test
    fun parsesUserInfoFromJdbcUrl() {
        val parsed = parsePostgresUrl(
            "jdbc:postgresql://kalendee:kalendee@localhost:5432/kalendee",
        )
        assertEquals("jdbc:postgresql://localhost:5432/kalendee", parsed.jdbcUrl)
        assertEquals("kalendee", parsed.user)
        assertEquals("kalendee", parsed.password)
    }

    @Test
    fun leavesUrlWithoutUserInfoUnchanged() {
        val raw = "jdbc:postgresql://127.0.0.1:5432/kalendee"
        val parsed = parsePostgresUrl(raw)
        assertEquals(raw, parsed.jdbcUrl)
        assertNull(parsed.user)
        assertNull(parsed.password)
    }

    @Test
    fun leavesH2MemUrlUnchanged() {
        val raw = "jdbc:h2:mem:kalendee;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
        val parsed = parsePostgresUrl(raw)
        assertEquals(raw, parsed.jdbcUrl)
        assertNull(parsed.user)
        assertNull(parsed.password)
    }

    @Test
    fun h2CompatibleSqlSplitsPostgresMultiAddColumn() {
        val sql = """
            ALTER TABLE users
                ADD COLUMN display_name TEXT,
                ADD COLUMN time_zone TEXT NOT NULL DEFAULT 'UTC';
            ALTER TABLE events
                ADD COLUMN url TEXT,
                ADD COLUMN recurrence_until TIMESTAMPTZ;
        """.trimIndent()
        val rewritten = h2CompatibleSql(sql)
        assertEquals(
            """
            ALTER TABLE users ADD COLUMN display_name TEXT;
            ALTER TABLE users ADD COLUMN time_zone TEXT NOT NULL DEFAULT 'UTC';
            ALTER TABLE events ADD COLUMN url TEXT;
            ALTER TABLE events ADD COLUMN recurrence_until TIMESTAMP WITH TIME ZONE;
            """.trimIndent(),
            rewritten,
        )
    }
}
