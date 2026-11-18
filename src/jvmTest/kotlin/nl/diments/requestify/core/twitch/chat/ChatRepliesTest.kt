package nl.diments.requestify.core.twitch.chat

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import nl.diments.requestify.core.intake.IntakeOutcome
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.NowPlaying
import nl.diments.requestify.core.playback.PlaybackSnapshot
import nl.diments.requestify.core.settings.ChatPermission

class ChatRepliesTest {
    private lateinit var savedLocale: Locale

    @BeforeTest fun setUp() { savedLocale = Locale.getDefault() }
    @AfterTest fun tearDown() { Locale.setDefault(savedLocale) }

    /** A track, artist and viewer named like commands: a reply must never start with one. */
    private val track = Track("t1", "spotify:track:t1", "!skip all", listOf("!remove 1"), 200_000, false, null)
    private val request = Request("r1", track, Requester("!blacklist", "viewer", "u1"), RequestSource.CHAT, null, 0)

    private val reasons = listOf(
        RejectReason.NotATrack("album"), RejectReason.NotATrack("unknown"), RejectReason.NoMatch, RejectReason.Unplayable,
        RejectReason.SpotifyUnavailable, RejectReason.TrackBlocked, RejectReason.ArtistBlocked, RejectReason.Explicit,
        RejectReason.TooShort(30_000), RejectReason.TooLong(420_000), RejectReason.Closed, RejectReason.QueueFull(25),
        RejectReason.ViewerLimit(2), RejectReason.Cooldown(95_000), RejectReason.Duplicate, RejectReason.BelowMinimum(100),
        RejectReason.ShortLinkUnresolved, RejectReason.BelowMinimumAmount(100, "EUR"), RejectReason.OtherCurrency("USD"),
    )

    private suspend fun everyReply(): List<String> {
        val replies = ChatReplies()
        val playing = NowPlaying(track, 1_000, true, request, 0)
        val snapshot = PlaybackSnapshot(listOf(request), request, request, emptyList(), playing)
        val ownMusic = snapshot.copy(nowPlaying = playing.copy(request = null), playing = null)
        val nothing = snapshot.copy(nowPlaying = null, queue = emptyList(), handedOver = null)
        return buildList {
            for (position in listOf(0, 1, 3)) {
                for (waitMs in listOf(0L, 400_000L)) {
                    add(replies.outcome(IntakeOutcome.Accepted(request, position, waitMs), null, refundedPoints = false))
                }
            }
            for (reason in reasons) {
                add(replies.outcome(IntakeOutcome.Rejected(reason, track, listed = true), null, refundedPoints = false))
                add(replies.outcome(IntakeOutcome.Rejected(reason, track, listed = true), null, refundedPoints = true))
            }
            add(replies.usage("sr", null))
            ChatPermission.entries.forEach { add(replies.denied(it, null)) }
            add(replies.song(snapshot, null))
            add(replies.song(ownMusic, null))
            add(replies.song(nothing, null))
            add(replies.queue(snapshot, "u1", null))
            add(replies.queue(nothing, "u1", null))
            add(replies.queue(snapshot.copy(handedOver = null), null, null))
            add(replies.wrongSong(request, null))
            add(replies.wrongSong(null, null))
            add(replies.skipped(track, null))
            add(replies.blacklisted(track, null))
            add(replies.blacklistFailed(null))
            add(replies.removed(request, 1, "remove", null))
            add(replies.removed(null, 4, "remove", null))
            add(replies.removed(null, null, "remove", null))
        }.filterNotNull()
    }

    @Test
    fun noReplyStartsWithACommand() = runBlocking {
        for (locale in listOf(Locale.ENGLISH, Locale.forLanguageTag("nl-NL"))) {
            Locale.setDefault(locale)
            for (reply in everyReply()) {
                assertFalse(reply.startsWith("!"), "$locale: \"$reply\" would be read as a command")
            }
        }
    }
}
