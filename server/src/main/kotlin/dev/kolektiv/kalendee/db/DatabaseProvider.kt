package dev.kolektiv.kalendee.db

import com.zaxxer.hikari.HikariDataSource
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

object DatabaseProvider {
    private val lock = ReentrantLock()

    @Volatile
    private var dataSource: HikariDataSource? = null

    @Volatile
    private var database: Database? = null

    private var settings: DatabaseSettings? = null

    fun connect(settings: DatabaseSettings): Database = lock.withLock {
        val current = dataSource
        if (current == null || !current.isRunning || settings != this.settings) {
            current?.close()
            val created = createDataSource(settings)
            Runtime.getRuntime().addShutdownHook(Thread { created.close() })
            dataSource = created
            this.settings = settings
            database = null
        }
        val source = checkNotNull(dataSource)
        migrate(source, settings)
        database ?: Database.connect(source).also {
            TransactionManager.defaultDatabase = it
            database = it
        }
    }

    @VisibleForTesting
    internal fun resetForTest() {
        lock.withLock {
            dataSource?.close()
            dataSource = null
            database = null
            settings = null
        }
    }
}
