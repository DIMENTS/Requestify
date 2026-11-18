package nl.diments.requestify.core.twitch.chat

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
            add(replies.blockedNotSkipped(track, null))
            add(replies.blacklistFailed(null))
            add(replies.removed(request, 1, "remove", null))
            add(replies.removed(null, 4, "remove", null))
            add(replies.removed(null, null, "remove", null))
        }.filterNotNull()
    }

    @Test
    fun blacklistWithoutASkipSaysBlockedOnly() = runBlocking {
        Locale.setDefault(Locale.forLanguageTag("nl-NL"))
        val text = ChatReplies().blockedNotSkipped(Track("t2", "spotify:track:t2", "Alpha", listOf("Artist"), 200_000, false, null), null)
        assertEquals("Geblokkeerd: Alpha – Artist", text)
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

    @Test
    fun donorNamesCannotBreakTheReply() = runBlocking {
        val names = listOf("", "   ", "!skip", "/me hi", "🎵", "a".repeat(300))
        val outcomes = listOf(
            IntakeOutcome.Accepted(request, 2, 400_000),
            IntakeOutcome.Accepted(request, 0, 0),
            IntakeOutcome.Rejected(RejectReason.BelowMinimumAmount(500, "EUR"), track, listed = true),
            IntakeOutcome.Rejected(RejectReason.TrackBlocked, track, listed = true),
        )
        val prefixes = mapOf(Locale.ENGLISH to "Donation request from ", Locale.forLanguageTag("nl-NL") to "Donatieverzoek van ")
        for ((locale, prefix) in prefixes) {
            Locale.setDefault(locale)
            for (name in names) {
                for (outcome in outcomes) {
                    val reply = assertNotNull(ChatReplies().donationOutcome(outcome, name), "$locale $name $outcome")
                    assertTrue(reply.startsWith(prefix), "$locale: \"$reply\"")
                    assertFalse(reply.startsWith("!") || reply.startsWith("/"), "$locale: \"$reply\"")
                    assertTrue(reply.length <= 500, "$locale: ${reply.length} characters")
                    assertFalse(name.length > 50 && name in reply, "a long name is cut")
                }
            }
            assertNull(ChatReplies().donationOutcome(IntakeOutcome.Ignored, "Jan"))
            assertNull(ChatReplies().donationOutcome(IntakeOutcome.Pending, "Jan"))
        }
    }
}
