package dev.kolektiv.kalendee.client

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO

internal actual fun platformHttpEngine(): HttpClientEngine = sharedEngine

private val sharedEngine: HttpClientEngine by lazy { CIO.create() }
