package dev.kolektiv.kalendee.client

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun platformHttpEngine(): HttpClientEngine = sharedEngine

private val sharedEngine: HttpClientEngine by lazy { OkHttp.create() }
