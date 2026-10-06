package dev.kolektiv.kalendee.platform

import dev.kolektiv.kalendee.client.KeyValueStore

/** Returns the platform-backed store used for server accounts and sessions. */
expect fun platformKeyValueStore(): KeyValueStore
