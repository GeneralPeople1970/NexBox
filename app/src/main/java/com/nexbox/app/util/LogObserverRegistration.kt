package com.nexbox.app.util

/** Relay-worker confined. Notifications accelerate reconciliation but never gate transport. */
internal class LogObserverRegistration(
    private val register: () -> Unit,
    private val unregister: () -> Unit
) : AutoCloseable {
    private var registered = false

    fun ensureRegistered() {
        if (registered) return
        try {
            register()
            registered = true
        } catch (_: Exception) {
            // The next relay flush retries; this flush can still read config and send its batch.
        }
    }

    override fun close() {
        if (!registered) return
        registered = false
        try {
            unregister()
        } catch (_: Exception) {
            // A dead content service may already have removed the observer.
        }
    }
}
