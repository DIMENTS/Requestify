package nl.diments.requestify.core.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout

/**
 * The updater's own client: a release's MSI is about 90 MB, so there is no limit on the whole request (the app's
 * shared client stops any request after 15 s), only on connecting and on a stalled download.
 */
fun updateHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 60_000
    }
}
