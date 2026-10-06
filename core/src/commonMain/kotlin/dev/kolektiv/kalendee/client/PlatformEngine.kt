package dev.kolektiv.kalendee.client

import io.ktor.client.engine.HttpClientEngine

internal expect fun platformHttpEngine(): HttpClientEngine
