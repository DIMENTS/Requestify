package nl.diments.requestify.core.donations.streamelements

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.test.Test
import kotlin.test.assertEquals

class AstroHttpClientTest {
    /** Astro's own pings are answered by Ktor but never seen; only our pings notice a connection that died silently. */
    @Test
    fun theAstroClientPingsSoADeadConnectionIsNoticed() {
        val http = astroHttpClient(MockEngine { respondOk() })
        assertEquals(30_000L, http.plugin(WebSockets).pingIntervalMillis)
    }
}
