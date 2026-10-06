package dev.kolektiv.kalendee.client

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

internal actual fun platformHttpEngine(): HttpClientEngine = sharedEngine

private val sharedEngine: HttpClientEngine by lazy { Darwin.create() }
