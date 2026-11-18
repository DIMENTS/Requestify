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
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.diments.requestify.core.model.IgnoredReason
import nl.diments.requestify.core.settings.KeyExpiryNotice
import nl.diments.requestify.core.log.LogConnectionState
import nl.diments.requestify.core.log.LogRecord
import nl.diments.requestify.core.log.LogService
import nl.diments.requestify.core.log.LogStep
import nl.diments.requestify.core.log.LogStreamChange
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.playback.RemoveReason
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
import nl.diments.requestify.core.settings.DonationSettings
import nl.diments.requestify.core.settings.DonationSourceKind
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
        val loaded = SettingsStore(JsonFileStore(file, Settings.serializer()) { Settings() }, inCallerSaveContext()).settings.value
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
        val loaded = SettingsStore(JsonFileStore(file, Settings.serializer()) { Settings() }, inCallerSaveContext()).settings.value
        assertEquals(Settings(themeMode = ThemeMode.Album), loaded)
        assertNothingMovedAside()
    }

    /** The Log's day files keep 30 days of these: each record, step and enum keeps its saved name. */
    @Test
    fun logRecordNamesArePinned() {
        fun typeOf(json: String) = AppJson.instance.parseToJsonElement(json).jsonObject.getValue("type").jsonPrimitive.content
        val records = mapOf(
            LogRecord.RequestReceived("r", 1, RequestSource.CHAT, Requester("Jan"), "x") to "request_received",
            LogRecord.RequestStep("r", 1, LogStep.Played) to "request_step",
            LogRecord.ConnectionChanged("c", 1, LogService.Twitch, LogConnectionState.Lost) to "connection",
            LogRecord.StreamChanged("s", 1, LogStreamChange.Live) to "stream",
            LogRecord.GapNoticed("g", 1, RequestSource.BITS, null, 2) to "gap",
            LogRecord.AppStopped("a", 1, lastAliveEpochMs = 0, crashLine = null, restarted = false) to "app_stopped",
            LogRecord.AppUpdated("u", 1, from = "0.2.0", to = "0.2.1") to "app_updated",
            LogRecord.UpdateFailed("f", 1, version = "0.2.1", exitCode = 1603, reason = null) to "update_failed",
        )
        for ((record, name) in records) {
            val json = AppJson.instance.encodeToString(LogRecord.serializer(), record)
            assertEquals(name, typeOf(json))
            assertEquals(record, AppJson.instance.decodeFromString(LogRecord.serializer(), json))
        }
        val steps = mapOf(
            LogStep.Queued(1) to "queued",
            LogStep.Rejected(RejectReason.Duplicate, listed = true) to "rejected",
            LogStep.Pending to "pending",
            LogStep.Ignored() to "ignored",
            LogStep.HandedOver to "handed_over",
            LogStep.Played to "played",
            LogStep.Removed(RemoveReason.WrongSong) to "removed",
            LogStep.RefundPending(1) to "refund_pending",
            LogStep.Refunded to "refunded",
            LogStep.RefundGaveUp to "refund_gave_up",
            LogStep.Fulfilled to "fulfilled",
            LogStep.Resolved(track("t1", "Song", listOf("A"), 1)) to "track",
        )
        for ((step, name) in steps) assertEquals(name, typeOf(AppJson.instance.encodeToString(LogStep.serializer(), step)))
        val enums = listOf(
            AppJson.instance.encodeToString(LogService.serializer(), LogService.StreamElements) to "\"streamelements\"",
            AppJson.instance.encodeToString(LogConnectionState.serializer(), LogConnectionState.KeyExpiring) to "\"key_expiring\"",
            AppJson.instance.encodeToString(LogStreamChange.serializer(), LogStreamChange.OpenedByHand) to "\"opened_by_hand\"",
            AppJson.instance.encodeToString(RemoveReason.serializer(), RemoveReason.StreamEnded) to "\"stream_ended\"",
        )
        for ((json, expected) in enums) assertEquals(expected, json)
    }

    /** Settings saved before donations existed read with the donation defaults. */
    @Test
    fun oldSettingsWithoutDonationsRead() {
        val file = installed("settings-v1.json", "settings.json")
        val loaded = SettingsStore(JsonFileStore(file, Settings.serializer()) { Settings() }, inCallerSaveContext()).settings.value
        assertEquals(DonationSettings(), loaded.donations)
        assertNothingMovedAside()
    }

    /** The running marker (running.json) keeps these names: an update must still read the one a crash left. */
    @Test
    fun runMarkerIsPinned() {
        val json = AppJson.instance.encodeToString(nl.diments.requestify.desktop.RunMarker.serializer(), nl.diments.requestify.desktop.RunMarker(1, 2, afterCrash = true, crashFile = "c.txt"))
        assertEquals("""{"startedAtEpochMs":1,"lastAliveEpochMs":2,"afterCrash":true,"crashFile":"c.txt"}""", json)
    }

    /** Which DonoLink expiry step was notified is kept in settings.json under these names. */
    @Test
    fun keyExpiryNoticeIsPinned() {
        val json = AppJson.instance.encodeToString(Settings.serializer(), Settings(keyExpiryNotified = KeyExpiryNotice(1_000, 7)))
        assertTrue(""""keyExpiryNotified":{"expiresAtEpochMs":1000,"stage":7}""" in json, json)
        assertEquals(null, AppJson.instance.decodeFromString(Settings.serializer(), "{}").keyExpiryNotified, "old settings read")
    }

    /** That the first-close hint was shown is kept in settings.json under this name. */
    @Test
    fun closeHintShownIsPinned() {
        val json = AppJson.instance.encodeToString(Settings.serializer(), Settings(closeHintShown = true))
        assertTrue(""""closeHintShown":true""" in json, json)
        assertEquals(false, AppJson.instance.decodeFromString(Settings.serializer(), "{}").closeHintShown)
    }

    /** Whether the streamer chose to start with Windows is kept in settings.json under this name. */
    @Test
    fun startWithWindowsIsPinned() {
        val json = AppJson.instance.encodeToString(Settings.serializer(), Settings(startWithWindows = false))
        assertTrue(""""startWithWindows":false""" in json, json)
        assertEquals(null, AppJson.instance.decodeFromString(Settings.serializer(), "{}").startWithWindows, "never chosen")
    }

    /** Why a donation was no request is kept in the Log's day files under these names. */
    @Test
    fun ignoredReasonsArePinned() {
        val json = AppJson.instance.encodeToString(LogStep.serializer(), LogStep.Ignored(IgnoredReason.ModerationRejected))
        assertEquals("""{"type":"ignored","reason":"moderation_rejected"}""", json)
        assertEquals(LogStep.Ignored(), AppJson.instance.decodeFromString(LogStep.serializer(), """{"type":"ignored"}"""), "plan-3 rows read")
        assertEquals("\"tip_failed\"", AppJson.instance.encodeToString(IgnoredReason.serializer(), IgnoredReason.TipFailed))
    }

    /** The start of the stream the gate follows is kept in stream.json under this name. */
    @Test
    fun streamStartedAtIsPinned() {
        val json = AppJson.instance.encodeToString(nl.diments.requestify.core.stream.StreamState.serializer(), nl.diments.requestify.core.stream.StreamState(streamStartedAtEpochMs = 5))
        assertTrue(""""streamStartedAtEpochMs":5""" in json, json)
    }

    /**
     * Every file Requestify keeps reads as written by this version: field names may never change (an update must read
     * what the last one left). Literal JSON, not an encoding of today's classes.
     */
    @Test
    fun everyPersistedTypeHasAFixture() {
        val json = AppJson.instance
        assertEquals(
            nl.diments.requestify.core.donations.DonationCursors(donoLink = nl.diments.requestify.core.donations.DonationCursor("2026-11-18T20:00:00Z", setOf("a"))),
            json.decodeFromString(nl.diments.requestify.core.donations.DonationCursors.serializer(), """{"donoLink":{"since":"2026-11-18T20:00:00Z","seenAtSince":["a"]},"streamElements":null}"""),
        )
        assertEquals(
            nl.diments.requestify.core.stream.StreamState(live = true, override = false, overrideAfterStream = true, offlineSinceMs = 3, clearPostponed = true, openWithoutTwitch = false, lastSeenLiveMs = 4, streamStartedAtEpochMs = 5),
            json.decodeFromString(nl.diments.requestify.core.stream.StreamState.serializer(), """{"live":true,"override":false,"overrideAfterStream":true,"offlineSinceMs":3,"clearPostponed":true,"openWithoutTwitch":false,"lastSeenLiveMs":4,"streamStartedAtEpochMs":5}"""),
        )
        assertEquals(
            nl.diments.requestify.core.twitch.points.OutboxEntry("rw", "rd", nl.diments.requestify.core.twitch.RedemptionStatus.CANCELED, tries = 2),
            json.decodeFromString(nl.diments.requestify.core.twitch.points.OutboxEntry.serializer(), """{"rewardId":"rw","redemptionId":"rd","status":"CANCELED","tries":2}"""),
        )
        assertEquals(
            nl.diments.requestify.desktop.RunMarker(1, 2, afterCrash = true, crashFile = "c.txt"),
            json.decodeFromString(nl.diments.requestify.desktop.RunMarker.serializer(), """{"startedAtEpochMs":1,"lastAliveEpochMs":2,"afterCrash":true,"crashFile":"c.txt"}"""),
        )
    }

    /** The uninstaller's note of the autostart it removed (autostart-removed.json): a later version restores from it. */
    @Test
    fun autostartRemovedMarkerIsPinned() {
        val serializer = nl.diments.requestify.desktop.AutostartRemovedMarker.serializer()
        assertEquals(
            """{"state":"on"}""",
            AppJson.instance.encodeToString(serializer, nl.diments.requestify.desktop.AutostartRemovedMarker(nl.diments.requestify.desktop.RemovedAutostart.On)),
        )
        assertEquals(
            nl.diments.requestify.desktop.AutostartRemovedMarker(nl.diments.requestify.desktop.RemovedAutostart.DisabledInTaskManager),
            AppJson.instance.decodeFromString(serializer, """{"state":"disabled_in_task_manager"}"""),
        )
    }

    /**
     * The updater's notes in %LOCALAPPDATA%\Requestify-updates: a new version reads what the old one left (the
     * pending update it installs, and the "updated" card after it).
     */
    @Test
    fun updateNotesArePinned() {
        val pending = """{"version":"0.2.0","msiFile":"Requestify-0.2.0.msi","sha256":"ab","notes":"n","pageUrl":"u","attempts":1}"""
        assertEquals(
            nl.diments.requestify.core.update.PendingUpdate("0.2.0", "Requestify-0.2.0.msi", "ab", "n", "u", attempts = 1),
            AppJson.instance.decodeFromString(nl.diments.requestify.core.update.PendingUpdate.serializer(), pending),
        )
        assertEquals(
            nl.diments.requestify.core.update.JustUpdated("0.1.0", "0.2.0", "n"),
            AppJson.instance.decodeFromString(nl.diments.requestify.core.update.JustUpdated.serializer(), """{"from":"0.1.0","to":"0.2.0","notes":"n"}"""),
        )
    }

    /** A file that could not be saved is logged under this service name. */
    @Test
    fun filesServiceNameIsPinned() {
        assertEquals("\"files\"", AppJson.instance.encodeToString(LogService.serializer(), LogService.Files))
    }

    /** The chosen donation source is saved in settings.json under these names. */
    @Test
    fun donationNamesArePinned() {
        val names = mapOf(
            DonationSourceKind.None to "\"none\"",
            DonationSourceKind.DonoLink to "\"donolink\"",
            DonationSourceKind.StreamElements to "\"streamelements\"",
        )
        assertEquals(DonationSourceKind.entries.toSet(), names.keys)
        for ((kind, json) in names) {
            assertEquals(json, AppJson.instance.encodeToString(DonationSourceKind.serializer(), kind))
            assertEquals(kind, AppJson.instance.decodeFromString(DonationSourceKind.serializer(), json))
        }
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
            RejectReason.BelowMinimumAmount(100, "EUR") to """{"type":"below_minimum_amount","minCents":100,"currency":"EUR"}""",
            RejectReason.OtherCurrency("USD") to """{"type":"other_currency","currency":"USD"}""",
            RejectReason.ShortLinkUnresolved to """{"type":"short_link"}""",
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
