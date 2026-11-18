package nl.diments.requestify.core.storage

import java.nio.file.FileSystemException
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Saves in the caller, before `save` returns, as every store did before plan 4: for tests that read a file right
 * after a change and do not test the background saving itself. Never used by the app.
 */
fun inCallerSaveContext(clock: () -> Long = System::currentTimeMillis): SaveContext =
    SaveContext(CoroutineScope(Dispatchers.Unconfined), clock, Dispatchers.Unconfined)

/**
 * Deletes a test's temp folder, giving a background save that is still finishing (another thread wrote the change
 * that the test already saw in memory) a moment to let go of its file. Since plan 4 a change is visible before it is
 * written, so a UI test can end while a save from a worker thread is in flight.
 */
@OptIn(ExperimentalPathApi::class)
fun Path.deleteTestDir() {
    repeat(20) { attempt ->
        try {
            deleteRecursively()
            return
        } catch (e: FileSystemException) {
            if (attempt == 19) throw e
            Thread.sleep(50)
        }
    }
}
