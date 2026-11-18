package nl.diments.requestify.core.intake

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.builtins.ListSerializer
import nl.diments.requestify.core.storage.inCallerSaveContext
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.storage.JsonFileStore

@OptIn(ExperimentalPathApi::class)
class PendingResolveStoreTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-pending") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun store() = PendingResolveStore(
        JsonFileStore(dir.resolve("pending.json"), ListSerializer(IncomingRequest.serializer()), retryDelaysMs = emptyList()) { emptyList() },
        inCallerSaveContext(),
    )

    private fun incoming(id: String) = IncomingRequest(
        id, "https://open.spotify.com/track/abc", linkOnly = true, requester = Requester("V", "v", "u1"),
        source = RequestSource.BITS, payment = Payment.Bits(500), sourceRef = "cheer-1", receivedAtEpochMs = 5,
        replyTo = "msg-1",
    )

    @Test
    fun roundTrip() {
        val store = store()
        assertEquals(emptyList(), store.pending.value)
        store.add(incoming("a"))
        store.add(incoming("b"))
        store.add(incoming("a"))
        assertEquals(listOf(incoming("a"), incoming("b")), store().pending.value)
        store.remove("a")
        assertEquals(listOf(incoming("b")), store().pending.value)
    }
}
