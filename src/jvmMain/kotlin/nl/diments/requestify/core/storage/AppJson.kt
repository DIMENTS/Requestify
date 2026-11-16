package nl.diments.requestify.core.storage

import kotlinx.serialization.json.Json

/** Shared Json config: tolerant of fields added by newer versions of the app. */
internal object AppJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
