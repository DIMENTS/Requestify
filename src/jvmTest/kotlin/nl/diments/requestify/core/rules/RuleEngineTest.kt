package nl.diments.requestify.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import nl.diments.requestify.core.blacklist.Blacklist
import nl.diments.requestify.core.blacklist.BlockedArtist
import nl.diments.requestify.core.blacklist.BlockedViewer
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.PlaybackSnapshot
import nl.diments.requestify.core.queue.PlayedEntry

class RuleEngineTest {
    private val now = 10_000_000_000L
    private val viewer = Requester("Kaas", "kaas", twitchUserId = "u1")

    private fun track(
        id: String = "t",
        durationMs: Long = 200_000,
        explicit: Boolean = false,
        artists: List<String> = listOf("Artist"),
        artistIds: List<String> = listOf("a1"),
    ) = Track(id, "spotify:track:$id", "Song $id", artists, durationMs, explicit, null, artistIds)

    private fun request(
        id: String = "r",
        track: Track = track(),
        requester: Requester = viewer,
        payment: Payment? = null,
    ) = Request(id, track, requester, RequestSource.CHAT, payment, now)

    private fun snapshot(
        queue: List<Request> = emptyList(),
        handedOver: Request? = null,
        playing: Request? = null,
        history: List<PlayedEntry> = emptyList(),
    ) = PlaybackSnapshot(queue, handedOver, playing, history, nowPlaying = null)

    private fun ctx(
        rules: Rules = Rules(),
        open: Boolean = true,
        snapshot: PlaybackSnapshot = snapshot(),
        lastAcceptedAtMs: Long? = null,
        blacklist: Blacklist = Blacklist(),
    ) = RuleContext(rules, open, snapshot, lastAcceptedAtMs, blacklist, now)

    @Test
    fun acceptsAnOrdinaryRequest() = assertNull(RuleEngine.decide(request(), ctx()))

    @Test
    fun contentFiltersApplyToPaid() {
        val blacklist = Blacklist(artists = listOf(BlockedArtist("a1", "Artist", 1)))
        assertEquals(
            RejectReason.ArtistBlocked,
            RuleEngine.decide(request(payment = Payment.Bits(500)), ctx(blacklist = blacklist)),
        )
    }

    @Test
    fun paidSkipsLimits() {
        val full = (1..25).map { request("q$it", track("q$it")) }
        val atLimit = snapshot(queue = full + request("v1", track("x1")) + request("v2", track("x2")))
        assertNull(RuleEngine.decide(request(payment = Payment.Bits(500)), ctx(open = false, snapshot = atLimit)))
    }

    @Test
    fun orderIsBlacklistThenExplicitThenLength() {
        val blacklist = Blacklist(viewers = listOf(BlockedViewer("u1", "kaas", 1)))
        val r = request(track = track(durationMs = 480_000, explicit = true))
        val c = ctx(rules = Rules(blockExplicit = true), blacklist = blacklist)
        assertEquals(RejectReason.ViewerBlocked, RuleEngine.decide(r, c))
        assertEquals(RejectReason.Explicit, RuleEngine.decide(r, c.copy(blacklist = Blacklist())))
        assertEquals(RejectReason.TooLong(420_000), RuleEngine.decide(r, c.copy(blacklist = Blacklist(), rules = Rules())))
    }

    @Test
    fun freeWhileClosedIsClosedFirst() {
        val full = snapshot(queue = (1..25).map { request("q$it", track("q$it"), Requester("o$it", twitchUserId = "o$it")) })
        assertEquals(RejectReason.Closed, RuleEngine.decide(request(), ctx(open = false, snapshot = full)))
    }

    @Test
    fun queueFullAt25() {
        fun queueOf(n: Int) = snapshot(queue = (1..n).map { request("q$it", track("q$it"), Requester("o$it", twitchUserId = "o$it")) })
        assertNull(RuleEngine.decide(request(), ctx(snapshot = queueOf(24))))
        assertEquals(RejectReason.QueueFull(25), RuleEngine.decide(request(), ctx(snapshot = queueOf(25))))
    }

    @Test
    fun viewerLimitCountsQueuedAndHandedOverNotPlaying() {
        val mine1 = request("m1", track("m1"))
        val mine2 = request("m2", track("m2"))
        assertEquals(
            RejectReason.ViewerLimit(2),
            RuleEngine.decide(request(), ctx(snapshot = snapshot(queue = listOf(mine1, mine2)))),
        )
        assertEquals(
            RejectReason.ViewerLimit(2),
            RuleEngine.decide(request(), ctx(snapshot = snapshot(queue = listOf(mine1), handedOver = mine2))),
        )
        assertNull(RuleEngine.decide(request(), ctx(snapshot = snapshot(queue = listOf(mine1), playing = mine2))))
    }

    @Test
    fun cooldownReportsRemaining() {
        val rules = Rules(cooldownMinutes = 5)
        assertEquals(
            RejectReason.Cooldown(180_000),
            RuleEngine.decide(request(), ctx(rules = rules, lastAcceptedAtMs = now - 120_000)),
        )
        assertNull(RuleEngine.decide(request(), ctx(rules = rules, lastAcceptedAtMs = now - 300_000)))
        assertNull(RuleEngine.decide(request(), ctx(lastAcceptedAtMs = now - 1_000)))
    }

    @Test
    fun duplicateQueuedHandedOverPlayingOrPlayedWithin60Min() {
        val other = Requester("Other", twitchUserId = "o")
        val same = track("dup")
        val asked = request(track = same)
        fun held(r: Request) = request("h", r.track, other)
        assertEquals(RejectReason.Duplicate, RuleEngine.decide(asked, ctx(snapshot = snapshot(queue = listOf(held(asked))))))
        assertEquals(RejectReason.Duplicate, RuleEngine.decide(asked, ctx(snapshot = snapshot(handedOver = held(asked)))))
        assertEquals(RejectReason.Duplicate, RuleEngine.decide(asked, ctx(snapshot = snapshot(playing = held(asked)))))

        fun playedAgo(minutes: Long) = snapshot(history = listOf(PlayedEntry(held(asked), now - minutes * 60_000)))
        assertEquals(RejectReason.Duplicate, RuleEngine.decide(asked, ctx(snapshot = playedAgo(59))))
        assertNull(RuleEngine.decide(asked, ctx(snapshot = playedAgo(61))))
        assertNull(RuleEngine.decide(asked, ctx(rules = Rules(noDuplicates = false), snapshot = playedAgo(59))))
    }

    @Test
    fun durationBounds() {
        fun decide(ms: Long) = RuleEngine.decide(request(track = track(durationMs = ms)), ctx())
        assertEquals(RejectReason.TooShort(30_000), decide(29_000))
        assertNull(decide(30_000))
        assertNull(decide(420_000))
        assertEquals(RejectReason.TooLong(420_000), decide(421_000))
    }

    @Test
    fun explicitOnlyWhenBlocked() {
        val r = request(track = track(explicit = true))
        assertNull(RuleEngine.decide(r, ctx()))
        assertEquals(RejectReason.Explicit, RuleEngine.decide(r, ctx(rules = Rules(blockExplicit = true))))
    }
}
