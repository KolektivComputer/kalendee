package dev.kolektiv.kalendee.platform

import dev.kolektiv.kalendee.client.KeyValueStore
import platform.Foundation.NSUserDefaults

private class UserDefaultsKeyValueStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : KeyValueStore {
    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}

actual fun platformKeyValueStore(): KeyValueStore = UserDefaultsKeyValueStore()
