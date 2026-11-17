package nl.diments.requestify.core.twitch.chat

import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.builtins.ListSerializer
import nl.diments.requestify.core.blacklist.Blacklist
import nl.diments.requestify.core.blacklist.BlacklistStore
import nl.diments.requestify.core.intake.FakeSpotifyCatalog
import nl.diments.requestify.core.intake.IncomingRequest
import nl.diments.requestify.core.intake.PendingResolveStore
import nl.diments.requestify.core.intake.RejectedEntry
import nl.diments.requestify.core.intake.RejectedStore
import nl.diments.requestify.core.intake.RequestIntake
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.FakeSpotifyPlayer
import nl.diments.requestify.core.playback.PlaybackController
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.QueueStore
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.twitch.eventsub.EventSubEvent

/**
 * Real intake, controller and stores on temp files in virtual time, a fake Spotify, and a mock Twitch API that records
 * the chat sends. The chat or Bits source under test is built by the test from [events], [intake], [replies] and
 * [sender].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TwitchTestRig(scope: TestScope, dir: Path) {
    val events = MutableSharedFlow<EventSubEvent>(extraBufferCapacity = 64)
    val twitch = RecordingTwitchApi()
    val catalog = FakeSpotifyCatalog()
    val settings = SettingsStore(JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() })
    val open = MutableStateFlow(true)
    val player = FakeSpotifyPlayer { scope.testScheduler.currentTime }
    val controller = PlaybackController(
        player = player,
        store = QueueStore(JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer()) { PersistedPlayback() }),
        requestsOpen = open,
        paidPriority = { true },
        scope = scope.backgroundScope,
        clock = { scope.testScheduler.currentTime },
        ioDispatcher = StandardTestDispatcher(scope.testScheduler),
    )
    val blacklist = BlacklistStore(JsonFileStore(dir.resolve("blacklist.json"), Blacklist.serializer()) { Blacklist() })
    val rejected = RejectedStore(
        JsonFileStore(dir.resolve("rejected.json"), ListSerializer(RejectedEntry.serializer())) { emptyList() },
    )
    val intake = RequestIntake(
        controller, catalog, settings, blacklist, rejected,
        PendingResolveStore(
            JsonFileStore(dir.resolve("pending.json"), ListSerializer(IncomingRequest.serializer())) { emptyList() },
        ),
        open,
        CoroutineScope(scope.backgroundScope.coroutineContext + Job(scope.backgroundScope.coroutineContext[Job])),
        clock = { scope.testScheduler.currentTime },
    )
    val replies = ChatReplies()
    val sender = ChatReplySender(twitch.api, scope.backgroundScope)

    val queued get() = controller.queue.value.items
    val rejectedEntries: List<RejectedEntry> get() = rejected.entries.value

    fun track(id: String, name: String) =
        Track(id, "spotify:track:$id", name, listOf("Artist $name"), 200_000, false, null, listOf("art-$id"))
}
