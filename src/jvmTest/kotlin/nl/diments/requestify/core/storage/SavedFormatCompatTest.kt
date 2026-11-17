package nl.diments.requestify.core.storage

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.PlayedEntry
import nl.diments.requestify.core.queue.QueueStore
import nl.diments.requestify.core.queue.RequestQueue
import nl.diments.requestify.core.settings.AppLanguage
import nl.diments.requestify.core.settings.DarkMode
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.settings.ThemeMode

/**
 * Files written by version 1 (checked in under `compat/`) must stay readable by every later version: a type
 * that is renamed or moved to another package must keep its saved name, or saved queues and settings are lost.
 */
@OptIn(ExperimentalPathApi::class)
class SavedFormatCompatTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-compat") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    /** Copies the checked-in [fixture] to [fileName] in the data folder, as an earlier version left it. */
    private fun installed(fixture: String, fileName: String): Path {
        val text = checkNotNull(javaClass.getResourceAsStream("/compat/$fixture")) { "missing fixture $fixture" }
            .use { it.readBytes().decodeToString() }
        return dir.resolve(fileName).also { it.writeText(text) }
    }

    private fun assertNothingMovedAside() =
        assertEquals(emptyList(), dir.listDirectoryEntries("*.corrupt-*").map { it.name })

    private fun track(id: String, name: String, artists: List<String>, durationMs: Long, explicit: Boolean = false, art: String? = null) =
        Track(id, "spotify:track:$id", name, artists, durationMs, explicit, art)

    @Test
    fun queueV1IsReadWithEveryPaymentKind() {
        val file = installed("queue-v1.json", "queue.json")
        val loaded = QueueStore(JsonFileStore(file, PersistedPlayback.serializer()) { PersistedPlayback() }).load()

        val bits = Request(
            "paid-bits",
            track("t1", "Blinding Lights", listOf("The Weeknd"), 200_040, art = "https://i.scdn.co/image/a1"),
            Requester("kaasboer", "kaasboer"),
            RequestSource.BITS,
            Payment.Bits(500),
            createdAtEpochMs = 1_763_290_000_000,
            sourceRef = "cheer-1",
        )
        val money = Request(
            "paid-money",
            track("t2", "Midnight City", listOf("M83"), 243_000, explicit = true),
            Requester("Viewer Two"),
            RequestSource.DONOLINK,
            Payment.Money(500, "EUR"),
            createdAtEpochMs = 1_763_290_001_000,
        )
        val free = Request(
            "free",
            track("t3", "Song Three", listOf("Artist A", "Artist B"), 180_000),
            Requester(""),
            RequestSource.MANUAL,
            payment = null,
            createdAtEpochMs = 1_763_290_002_000,
        )
        val played = Request(
            "played",
            track("t4", "Song Four", listOf("Artist C"), 210_000),
            Requester("Viewer Four", "viewer4"),
            RequestSource.STREAMELEMENTS,
            Payment.Money(250, "USD"),
            createdAtEpochMs = 1_763_289_000_000,
            sourceRef = "se-tip-9",
        )
        assertEquals(
            PersistedPlayback(
                queue = RequestQueue(listOf(bits, money)),
                handedOver = free,
                playing = null,
                history = listOf(PlayedEntry(played, playedAtEpochMs = 1_763_289_500_000)),
            ),
            loaded,
        )
        assertNothingMovedAside()
    }

    @Test
    fun oldQueueWithoutNewFieldsStillReads() {
        val file = dir.resolve("queue.json")
        file.writeText(
            """{"queue":{"items":[{"id":"r1","track":{"id":"t1","uri":"spotify:track:t1","name":"S","artists":["A"],""" +
                """"durationMs":1000,"explicit":false,"albumArtUrl":null},"requester":{"displayName":"V","login":"v"},""" +
                """"source":"CHAT","payment":null,"createdAtEpochMs":5}]}}""",
        )
        val loaded = QueueStore(JsonFileStore(file, PersistedPlayback.serializer()) { PersistedPlayback() }).load()
        val request = loaded.queue.items.single()
        assertEquals(emptyList(), request.track.artistIds)
        assertEquals(null, request.requester.twitchUserId)
        assertEquals(false, request.rulesWaived)
        assertNothingMovedAside()
    }

    @Test
    fun settingsV1WithACustomColourIsRead() {
        val file = installed("settings-v1.json", "settings.json")
        val loaded = SettingsStore(JsonFileStore(file, Settings.serializer()) { Settings() }).settings.value
        assertEquals(
            Settings(
                spotifyClientId = "0123456789abcdef0123456789abcdef",
                themeMode = ThemeMode.Custom(-10011977),
                darkMode = DarkMode.Dark,
                language = AppLanguage.Dutch,
                paidPriority = false,
            ),
            loaded,
        )
        assertNothingMovedAside()
    }

    @Test
    fun settingsV1WithTheAlbumColourIsRead() {
        val file = installed("settings-v1-album.json", "settings.json")
        val loaded = SettingsStore(JsonFileStore(file, Settings.serializer()) { Settings() }).settings.value
        assertEquals(Settings(themeMode = ThemeMode.Album), loaded)
        assertNothingMovedAside()
    }

    /** Rejections are saved in rejected.json: each reason keeps its name and fields. */
    @Test
    fun everyRejectReasonKeepsItsSavedName() {
        val saved = listOf(
            RejectReason.NotATrack("album") to """{"type":"not_a_track","kind":"album"}""",
            RejectReason.NoMatch to """{"type":"no_match"}""",
            RejectReason.Unplayable to """{"type":"unplayable"}""",
            RejectReason.SpotifyUnavailable to """{"type":"spotify_unavailable"}""",
            RejectReason.ViewerBlocked to """{"type":"viewer_blocked"}""",
            RejectReason.TrackBlocked to """{"type":"track_blocked"}""",
            RejectReason.ArtistBlocked to """{"type":"artist_blocked"}""",
            RejectReason.Explicit to """{"type":"explicit"}""",
            RejectReason.TooShort(30_000) to """{"type":"too_short","minMs":30000}""",
            RejectReason.TooLong(420_000) to """{"type":"too_long","maxMs":420000}""",
            RejectReason.Closed to """{"type":"closed"}""",
            RejectReason.QueueFull(25) to """{"type":"queue_full","max":25}""",
            RejectReason.ViewerLimit(2) to """{"type":"viewer_limit","max":2}""",
            RejectReason.Cooldown(180_000) to """{"type":"cooldown","remainingMs":180000}""",
            RejectReason.Duplicate to """{"type":"duplicate"}""",
            RejectReason.BelowMinimum(100) to """{"type":"below_minimum","minBits":100}""",
        )
        val subclasses = RejectReason.serializer().descriptor.getElementDescriptor(1) // the sealed subclasses
        val checkedNames = saved.map { (_, json) ->
            AppJson.instance.parseToJsonElement(json).jsonObject.getValue("type").jsonPrimitive.content
        }
        assertEquals(
            (0 until subclasses.elementsCount).map(subclasses::getElementName).sorted(),
            checkedNames.sorted(),
            "every reason is checked here",
        )
        for ((reason, json) in saved) {
            assertEquals(json, AppJson.instance.encodeToString(RejectReason.serializer(), reason))
            assertEquals(reason, AppJson.instance.decodeFromString(RejectReason.serializer(), json))
        }
    }
}
