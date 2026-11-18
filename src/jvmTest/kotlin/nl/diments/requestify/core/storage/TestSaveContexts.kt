package nl.diments.requestify.core.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Saves in the caller, before `save` returns, as every store did before plan 4: for tests that read a file right
 * after a change and do not test the background saving itself. Never used by the app.
 */
fun inCallerSaveContext(clock: () -> Long = System::currentTimeMillis): SaveContext =
    SaveContext(CoroutineScope(Dispatchers.Unconfined), clock, Dispatchers.Unconfined)
