package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.db.DatabaseProvider
import dev.kolektiv.kalendee.db.DatabaseSettings
import dev.kolektiv.kalendee.db.UsersTable
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

class DatabaseProviderTest {
    @AfterTest
    fun resetProvider() {
        DatabaseProvider.resetForTest()
    }

    @Test
    fun connectReusesTheSameDatabaseAndPool() = runBlocking {
        val settings = DatabaseSettings(
            url = h2TestUrl(),
            user = "sa",
            password = "",
            migrationsLocation = writeH2Migrations(Files.createTempDirectory("kalendee-h2-migrations")).toString(),
        )
        DatabaseProvider.resetForTest()

        val first = DatabaseProvider.connect(settings)
        assertEquals(0, suspendTransaction(db = first) { UsersTable.selectAll().count() })

        val second = DatabaseProvider.connect(settings)
        assertSame(first, second)
        assertEquals(0, suspendTransaction(db = second) { UsersTable.selectAll().count() })
    }
}
