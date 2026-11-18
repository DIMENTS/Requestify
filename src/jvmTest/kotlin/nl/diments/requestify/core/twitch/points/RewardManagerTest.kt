package nl.diments.requestify.core.twitch.points

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import nl.diments.requestify.core.storage.inCallerSaveContext
import nl.diments.requestify.core.settings.AppLanguage
import nl.diments.requestify.core.settings.ChannelPointsSettings
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.settings.TwitchSettings
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.twitch.eventsub.EventSubEvent

@OptIn(ExperimentalPathApi::class)
class RewardManagerTest {
    private lateinit var dir: Path
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeTest fun setUp() {
        dir = createTempDirectory("requestify-rewards")
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private class Harness(dir: Path, scope: CoroutineScope, language: AppLanguage, points: ChannelPointsSettings) {
        val helix = FakeRewardsHelix()
        val settings = SettingsStore(JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() }, inCallerSaveContext())
            .apply { update { it.copy(twitch = TwitchSettings(channelPoints = points)) } }
        val events = MutableSharedFlow<EventSubEvent>(extraBufferCapacity = 16)
        val open = MutableStateFlow(true)
        var rewardChanges = 0
        val manager = RewardManager(helix.api, settings, events, { rewardChanges++ }, open, { language }, scope, retryFirstMs = 20, retryMaxMs = 80)
        val points get() = settings.settings.value.twitch.channelPoints
    }

    private fun harness(language: AppLanguage = AppLanguage.English, points: ChannelPointsSettings = ChannelPointsSettings()) =
        Harness(dir.resolve("h${counter++}").also { it.toFile().mkdirs() }, scope, language, points)

    private var counter = 0

    private val managedPoints = ChannelPointsSettings(
        enabled = true,
        rewardId = "rw-1",
        managed = true,
        title = "Song request",
        cost = 500,
        prompt = "Type a song",
    )

    private fun Harness.withOwnReward() = apply {
        helix.rewards += helix.reward("rw-1")
        helix.manageable += "rw-1"
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        repeat(600) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail("timed out waiting for $what")
    }

    @Test
    fun createNewUsesLanguageDefaultsAndLinksManaged() = runBlocking {
        val dutch = harness(AppLanguage.Dutch)
        assertEquals(RewardActionResult.Ok, dutch.manager.createNew())
        val body = dutch.helix.creates.single().body!!
        assertEquals("Nummerverzoek", body.getValue("title").jsonPrimitive.content)
        assertEquals(500, body.getValue("cost").jsonPrimitive.int)
        assertEquals("Spotify-link of nummernaam", body.getValue("prompt").jsonPrimitive.content)
        assertTrue(body.getValue("is_user_input_required").jsonPrimitive.boolean)
        assertFalse(body.getValue("should_redemptions_skip_request_queue").jsonPrimitive.boolean)
        with(dutch.points) {
            assertEquals("new-1", rewardId)
            assertTrue(managed)
            assertTrue(enabled)
            assertEquals("Nummerverzoek", title)
            assertEquals(500, cost)
            assertEquals("Spotify-link of nummernaam", prompt)
            assertFalse(rewardRemoved)
        }
        assertEquals(1, dutch.rewardChanges)

        val english = harness(AppLanguage.English)
        english.manager.createNew()
        assertEquals("Song request", english.points.title)
        assertEquals("Spotify link or song name", english.points.prompt)
    }

    @Test
    fun duplicateTitleIsReported() = runBlocking {
        val h = harness()
        h.helix.createStatus = 400
        assertEquals(RewardActionResult.DuplicateTitle, h.manager.createNew())
        assertNull(h.points.rewardId)
        assertEquals(0, h.rewardChanges)

        h.helix.createStatus = 403
        assertEquals(RewardActionResult.NotAffiliate, h.manager.createNew())
        h.helix.createStatus = 500
        assertEquals(RewardActionResult.Failed, h.manager.createNew())
        assertNull(h.points.rewardId)
    }

    @Test
    fun scanSplitsManageable() = runBlocking {
        val h = harness().withOwnReward()
        h.helix.rewards += h.helix.reward("rw-2", title = "Hydrate", cost = 100, prompt = "")
        val scanned = h.manager.scan()!!
        assertEquals(listOf("rw-1" to true, "rw-2" to false), scanned.map { it.reward.id to it.manageable })
        assertEquals(setOf("true"), h.helix.calls.mapNotNull { it.query["only_manageable_rewards"] }.toSet())

        h.helix.listStatus = 500
        assertNull(h.manager.scan(), "Twitch could not answer")
    }

    @Test
    fun relinkOwnRewardCreatesNoDuplicate() = runBlocking {
        val h = harness().withOwnReward()
        val own = h.manager.scan()!!.single()
        h.manager.link(own)
        assertEquals(emptyList(), h.helix.creates)
        with(h.points) {
            assertEquals("rw-1", rewardId)
            assertTrue(managed)
            assertEquals("Song request", title)
            assertEquals("Type a song", prompt)
            assertFalse(rewardRemoved)
        }
        assertEquals(1, h.rewardChanges)
    }

    @Test
    fun copyCreatesOwnWithSameSettings() = runBlocking {
        val h = harness()
        val foreign = h.helix.reward("rw-9", title = "Pick a song", cost = 250, prompt = "Which one?")
        assertEquals(RewardActionResult.Ok, h.manager.copy(foreign))
        val body = h.helix.creates.single().body!!
        assertEquals("Pick a song", body.getValue("title").jsonPrimitive.content)
        assertEquals(250, body.getValue("cost").jsonPrimitive.int)
        assertEquals("Which one?", body.getValue("prompt").jsonPrimitive.content)
        with(h.points) {
            assertEquals("new-1", rewardId)
            assertTrue(managed)
            assertEquals(250, cost)
        }

        // A different name for the copy, when the old reward is still there.
        assertEquals(RewardActionResult.Ok, h.manager.copy(foreign, title = "Pick a song 2"))
        assertEquals("Pick a song 2", h.points.title)
    }

    @Test
    fun linkForeignIsUnmanagedAndNeverPatched() = runBlocking {
        val h = harness(points = ChannelPointsSettings(enabled = true))
        h.helix.rewards += h.helix.reward("rw-2", title = "Hydrate", cost = 100)
        val foreign = h.manager.scan()!!.single()
        assertFalse(foreign.manageable)
        h.manager.link(foreign)
        assertFalse(h.points.managed)
        assertEquals("rw-2", h.points.rewardId)
        assertEquals(1, h.rewardChanges)

        h.manager.start()
        h.open.value = false
        h.manager.setEnabled(false)
        h.manager.setEnabled(true)
        assertEquals(RewardActionResult.Failed, h.manager.edit("New name", null, null))
        Thread.sleep(150)
        assertEquals(emptyList(), h.helix.patches, "Twitch refuses a foreign reward's updates")
        assertEquals(emptyList(), h.helix.creates)
        assertTrue(h.points.enabled, "the switch is Requestify's own, and was switched back on")
        h.manager.stop()
    }

    @Test
    fun pausesWhenRequestsCloseAndUnpausesWhenOpen() = runBlocking {
        val h = harness(points = managedPoints).withOwnReward()
        h.manager.start()
        h.open.value = false
        eventually("the pause") { h.helix.rewards.single().isPaused }
        assertEquals(true, h.helix.patches.last().body!!.getValue("is_paused").jsonPrimitive.boolean)

        h.open.value = true
        eventually("the unpause") { !h.helix.rewards.single().isPaused }
        assertEquals(false, h.helix.patches.last().body!!.getValue("is_paused").jsonPrimitive.boolean)

        // Switched off: nothing to pause.
        h.manager.setEnabled(false)
        val patches = h.helix.patches.size
        h.open.value = false
        Thread.sleep(150)
        assertEquals(patches, h.helix.patches.size)
        h.manager.stop()
    }

    @Test
    fun switchingOffDisablesNeverDeletes() = runBlocking {
        val h = harness(points = managedPoints).withOwnReward()
        h.manager.setEnabled(false)
        assertFalse(h.points.enabled)
        assertFalse(h.helix.rewards.single().isEnabled)
        assertEquals(false, h.helix.patches.single().body!!.getValue("is_enabled").jsonPrimitive.boolean)

        h.manager.setEnabled(true)
        assertTrue(h.points.enabled)
        assertTrue(h.helix.rewards.single().isEnabled)
        assertEquals(emptyList(), h.helix.deletes)
        assertEquals("rw-1", h.points.rewardId, "the reward stays linked")
    }

    @Test
    fun editWritesOnlyWhatChangedAndReportsDuplicates() = runBlocking {
        val h = harness(points = managedPoints).withOwnReward()
        assertEquals(RewardActionResult.Ok, h.manager.edit("Songs!", 750, null))
        val body = h.helix.patches.single().body!!
        assertEquals(setOf("title", "cost"), body.keys)
        assertEquals("Songs!", h.points.title)
        assertEquals(750, h.points.cost)

        h.helix.updateStatus = 400
        assertEquals(RewardActionResult.DuplicateTitle, h.manager.edit("Hydrate", null, null))
        assertEquals("Songs!", h.points.title, "a refused name is not adopted")
    }

    @Test
    fun rewardUpdatedOnTwitchIsAdopted() = runBlocking {
        val h = harness(points = managedPoints).withOwnReward()
        h.manager.start()
        eventually("the first sync") { h.helix.patches.isNotEmpty() }
        val edited = h.helix.reward("rw-1", title = "Request a song", cost = 750, prompt = "Artist - title").copy(isPaused = true)
        h.helix.rewards[0] = edited // as it is on Twitch after the streamer's edit
        h.events.emit(EventSubEvent.RewardUpdated(edited))
        eventually("the adoption") { h.points.title == "Request a song" }
        assertEquals(750, h.points.cost)
        assertEquals("Artist - title", h.points.prompt)
        // Requests are open, but Twitch says paused: the app's own state is written back.
        eventually("the write-back") { !h.helix.rewards.single().isPaused }

        h.events.emit(EventSubEvent.RewardUpdated(h.helix.reward("rw-other", title = "Other", cost = 1)))
        Thread.sleep(100)
        assertEquals("Request a song", h.points.title, "another reward's update is not ours")
        h.manager.stop()
    }

    @Test
    fun rewardRemovedShowsCreateAgain() = runBlocking {
        val h = harness(points = managedPoints)
        h.manager.start()
        Thread.sleep(100)
        h.events.emit(EventSubEvent.RewardRemoved("rw-other"))
        Thread.sleep(100)
        assertFalse(h.points.rewardRemoved)

        h.events.emit(EventSubEvent.RewardRemoved("rw-1"))
        eventually("the removal") { h.points.rewardRemoved }
        assertEquals("rw-1", h.points.rewardId, "kept, so the card can say which reward went")
        h.manager.stop()

        // "Create again" makes a new reward (new id) with the name and cost it had.
        assertEquals(RewardActionResult.Ok, h.manager.createNew())
        with(h.points) {
            assertEquals("new-1", rewardId)
            assertFalse(rewardRemoved)
            assertEquals("Song request", title)
            assertEquals("Type a song", prompt)
        }
    }

    @Test
    fun aFailedPauseIsRetriedAndThenSent() = runBlocking {
        val h = harness(points = managedPoints).withOwnReward()
        h.helix.updateStatus = 500
        h.manager.start()
        h.open.value = false
        eventually("the refusal shows") { h.manager.outOfSync.value }
        eventually("a retry") { h.helix.patches.size >= 3 }
        assertFalse(h.helix.rewards.single().isPaused)

        h.helix.updateStatus = 200
        eventually("the pause lands") { h.helix.rewards.single().isPaused && !h.manager.outOfSync.value }
        h.manager.stop()
    }

    @Test
    fun aNewerWantedStateWinsOverAPendingRetry() = runBlocking {
        val h = harness(points = managedPoints).withOwnReward()
        h.helix.updateStatus = 500
        h.manager.start()
        h.open.value = false
        eventually("the refusal shows") { h.manager.outOfSync.value }
        h.open.value = true // requests are open again before the pause could be sent
        val before = h.helix.patches.size
        h.helix.updateStatus = 200
        eventually("in sync") { !h.manager.outOfSync.value }
        assertFalse(h.helix.rewards.single().isPaused)
        assertEquals(emptyList(), h.helix.patches.drop(before).filter { it.body!!["is_paused"]?.jsonPrimitive?.boolean == true })
        h.manager.stop()
    }

    @Test
    fun aRefusedLoginIsRetriedLikeAnyFailure() = runBlocking {
        val h = harness(points = managedPoints).withOwnReward()
        h.helix.updateStatus = 401 // a 401 that the forced refresh does not cure
        h.manager.start()
        h.open.value = false
        eventually("the refusal shows") { h.manager.outOfSync.value }
        val sent = h.helix.patches.size
        eventually("tried again, without a new start") { h.helix.patches.size > sent }

        h.helix.updateStatus = 200
        eventually("the pause lands") { h.helix.rewards.single().isPaused && !h.manager.outOfSync.value }
        h.manager.stop()
        assertFalse(h.manager.outOfSync.value)
    }

    @Test
    fun onlyAUniqueNameRefusalIsADuplicate() = runBlocking {
        val h = harness(points = managedPoints).withOwnReward()
        h.helix.createStatus = 400
        h.helix.badRequestMessage = "The message was held by AutoMod"
        assertEquals(RewardActionResult.Failed, h.manager.createNew())
        h.helix.updateStatus = 400
        assertEquals(RewardActionResult.Failed, h.manager.edit("Nope", null, null))
        h.helix.badRequestMessage = "The title must be unique (case-insensitive)"
        assertEquals(RewardActionResult.DuplicateTitle, h.manager.createNew())
        assertEquals(RewardActionResult.DuplicateTitle, h.manager.edit("Nope", null, null))
    }

    @Test
    fun aLogoutThatCannotGoOnResumesTheReward() = runBlocking {
        val h = harness(points = managedPoints).withOwnReward()
        h.manager.pauseForExit() // the logout pauses it first
        assertTrue(h.helix.rewards.single().isPaused)
        h.manager.resumeAfterExitPause() // the logout could not go on: still logged in
        assertEquals(h.open.value, !h.helix.rewards.single().isPaused, "back to what the requests want (open: not paused)")
    }

    @Test
    fun pausingForExitPausesOnlyAnOwnEnabledReward() = runBlocking {
        val h = harness(points = managedPoints).withOwnReward()
        h.manager.pauseForExit()
        assertTrue(h.helix.rewards.single().isPaused)

        val off = harness(points = managedPoints.copy(enabled = false)).withOwnReward()
        off.manager.pauseForExit()
        val foreign = harness(points = managedPoints.copy(managed = false)).withOwnReward()
        foreign.manager.pauseForExit()
        assertEquals(emptyList(), off.helix.patches + foreign.helix.patches)
    }
}
