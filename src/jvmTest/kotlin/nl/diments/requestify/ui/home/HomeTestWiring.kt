package nl.diments.requestify.ui.home

import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.ListSerializer
import nl.diments.requestify.core.storage.inCallerSaveContext
import nl.diments.requestify.core.blacklist.Blacklist
import nl.diments.requestify.core.blacklist.BlacklistStore
import nl.diments.requestify.core.intake.FakeShortLinks
import nl.diments.requestify.core.intake.IncomingRequest
import nl.diments.requestify.core.intake.PendingResolveStore
import nl.diments.requestify.core.intake.RejectedEntry
import nl.diments.requestify.core.intake.RejectedStore
import nl.diments.requestify.core.intake.RequestIntake
import nl.diments.requestify.core.playback.PlaybackController
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.spotify.ShortLinkResolver
import nl.diments.requestify.core.spotify.SpotifyCatalog
import nl.diments.requestify.core.storage.JsonFileStore

/** The intake side of a [HomeViewModel] under test, on files in [dir]. */
internal class IntakeParts(val blacklist: BlacklistStore, val rejected: RejectedStore, val intake: RequestIntake)

internal fun intakeParts(
    dir: Path,
    controller: PlaybackController,
    catalog: SpotifyCatalog,
    settings: SettingsStore,
    scope: CoroutineScope,
    shortLinks: ShortLinkResolver = FakeShortLinks(),
    clock: () -> Long,
): IntakeParts {
    val blacklist = BlacklistStore(JsonFileStore(dir.resolve("blacklist.json"), Blacklist.serializer()) { Blacklist() }, inCallerSaveContext())
    val rejected = RejectedStore(JsonFileStore(dir.resolve("rejected.json"), ListSerializer(RejectedEntry.serializer())) { emptyList() }, inCallerSaveContext())
    val pending = PendingResolveStore(
        JsonFileStore(dir.resolve("pending.json"), ListSerializer(IncomingRequest.serializer())) { emptyList() },
        inCallerSaveContext(),
    )
    val intake = RequestIntake(
        controller = controller,
        catalog = catalog,
        settings = settings,
        blacklist = blacklist,
        rejected = rejected,
        pending = pending,
        requestsOpen = MutableStateFlow(true),
        scope = scope,
        clock = clock,
        shortLinks = shortLinks,
    )
    return IntakeParts(blacklist, rejected, intake)
}
