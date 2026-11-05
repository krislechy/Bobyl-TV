package com.bobyl.tv

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** A successful external launch transfers resource cleanup to the player-result/service lifecycle. */
internal class PlaybackLaunchOwnership {
    private var transferred = false
    fun launch(launchPlayer: () -> Unit) {
        launchPlayer()
        transferred = true
    }
    fun cleanupBeforeTransfer(cleanup: () -> Unit) {
        if (!transferred) cleanup()
    }
}

/** Own the resource before dispatcher return can discard it through prompt cancellation. */
internal suspend fun <T : AutoCloseable> acquirePlaybackResource(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    interruptible: Boolean = false,
    create: () -> T,
    adopt: (T) -> Unit,
): T {
    var owned: T? = null
    try {
        val resource = if (interruptible) runInterruptible(dispatcher) { create().also { owned = it } }
            else withContext(dispatcher) { create().also { owned = it } }
        adopt(resource)
        owned = null // The service now owns cleanup, with no suspension during adoption.
        return resource
    } catch (failure: Throwable) {
        try { owned?.close() }
        catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
        throw failure
    }
}
