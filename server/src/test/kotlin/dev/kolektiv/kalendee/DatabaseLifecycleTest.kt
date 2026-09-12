package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.db.DatabaseSettings
import dev.kolektiv.kalendee.db.UsersTable
import dev.kolektiv.kalendee.db.createDataSource
import dev.kolektiv.kalendee.db.migrateAndConnect
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

class DatabaseLifecycleTest {
    @Test
    fun reconnectInstallsTheNewDatabaseAsDefault() = runBlocking {
        val settings = DatabaseSettings(
            url = h2TestUrl(),
            user = "sa",
            password = "",
            migrationsLocation = writeH2Migrations(Files.createTempDirectory("kalendee-h2-migrations")).toString(),
        )
        val previousDefault = TransactionManager.defaultDatabase

        val first = createDataSource(settings)
        val firstDatabase = migrateAndConnect(first, settings)
        TransactionManager.defaultDatabase = firstDatabase
        first.close()

        val second = createDataSource(settings)
        try {
            val secondDatabase = migrateAndConnect(second, settings)
            val count = suspendTransaction { UsersTable.selectAll().count() }
            assertEquals(0, count)
            assertEquals(secondDatabase, TransactionManager.defaultDatabase)
        } finally {
            second.close()
            TransactionManager.defaultDatabase = previousDefault
        }
    }
}
