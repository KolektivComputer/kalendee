package dev.kolektiv.kalendee.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

fun createDataSource(settings: DatabaseSettings): HikariDataSource {
    val config = HikariConfig().apply {
        jdbcUrl = settings.url
        username = settings.user
        password = settings.password
        maximumPoolSize = 10
        minimumIdle = 2
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        validate()
    }
    return HikariDataSource(config)
}

fun migrate(dataSource: HikariDataSource, settings: DatabaseSettings) {
    val location = settings.migrationsLocation.let { raw ->
        when {
            raw.startsWith("classpath:") || raw.startsWith("filesystem:") -> raw
            else -> "filesystem:$raw"
        }
    }
    Flyway.configure()
        .dataSource(dataSource)
        .locations(location)
        .load()
        .migrate()
}

fun migrateAndConnect(dataSource: HikariDataSource, settings: DatabaseSettings): Database {
    migrate(dataSource, settings)
    val database = Database.connect(dataSource)
    TransactionManager.defaultDatabase = database
    return database
}
