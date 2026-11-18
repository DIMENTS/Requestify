package nl.diments.requestify.core.twitch.chat

import kotlin.math.ceil
import nl.diments.requestify.core.intake.IntakeOutcome
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.NowPlayingKind
import nl.diments.requestify.core.playback.PlaybackSnapshot
import nl.diments.requestify.core.settings.ChatPermission
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.chat_added
import nl.diments.requestify.resources.chat_added_soon
import nl.diments.requestify.resources.chat_already_next
import nl.diments.requestify.resources.chat_blacklisted
import nl.diments.requestify.resources.chat_denied_followers
import nl.diments.requestify.resources.chat_denied_moderators
import nl.diments.requestify.resources.chat_denied_subscribers
import nl.diments.requestify.resources.chat_denied_vips
import nl.diments.requestify.resources.chat_queue_empty
import nl.diments.requestify.resources.chat_queue_item
import nl.diments.requestify.resources.chat_queue_next
import nl.diments.requestify.resources.chat_queue_you
import nl.diments.requestify.resources.chat_refunded
import nl.diments.requestify.resources.chat_remove_none
import nl.diments.requestify.resources.chat_remove_usage
import nl.diments.requestify.resources.chat_rejected_artist_blocked
import nl.diments.requestify.resources.chat_rejected_below_minimum
import nl.diments.requestify.resources.chat_rejected_closed
import nl.diments.requestify.resources.chat_rejected_cooldown
import nl.diments.requestify.resources.chat_rejected_duplicate
import nl.diments.requestify.resources.chat_rejected_explicit
import nl.diments.requestify.resources.chat_rejected_no_match
import nl.diments.requestify.resources.chat_rejected_not_a_track
import nl.diments.requestify.resources.chat_rejected_not_a_track_generic
import nl.diments.requestify.resources.chat_rejected_queue_full
import nl.diments.requestify.resources.chat_rejected_spotify_unavailable
import nl.diments.requestify.resources.chat_rejected_too_long
import nl.diments.requestify.resources.chat_rejected_too_short
import nl.diments.requestify.resources.chat_rejected_track_blocked
import nl.diments.requestify.resources.chat_rejected_unplayable
import nl.diments.requestify.resources.chat_rejected_viewer_limit
import nl.diments.requestify.resources.chat_song_nothing
import nl.diments.requestify.resources.chat_song_own_music
import nl.diments.requestify.resources.chat_song_plain
import nl.diments.requestify.resources.chat_song_requested
import nl.diments.requestify.resources.chat_skipped
import nl.diments.requestify.resources.chat_usage
import nl.diments.requestify.resources.chat_wrongsong_none
import nl.diments.requestify.resources.chat_wrongsong_removed
import nl.diments.requestify.resources.spotify_kind_album
import nl.diments.requestify.resources.spotify_kind_artist
import nl.diments.requestify.resources.spotify_kind_episode
import nl.diments.requestify.resources.spotify_kind_playlist
import nl.diments.requestify.resources.spotify_kind_show
import org.jetbrains.compose.resources.getString

/**
 * All chat texts, in the app language. None starts with `!`, so the app's own messages are never read back as
 * commands. [mention] ("@name ") is put in front of replies that are not threaded; a reply to a message already
 * shows who it answers.
 */
class ChatReplies {
    /** Null when nothing is said: ViewerBlocked, Ignored, Pending. */
    suspend fun outcome(outcome: IntakeOutcome, mention: String?, refundedPoints: Boolean): String? = when (outcome) {
        is IntakeOutcome.Accepted -> mentioned(mention, accepted(outcome))
        is IntakeOutcome.Rejected -> {
            // Not listed = a free request of a blocked viewer: no audience for trolls, whatever the reason.
            if (outcome.reason == RejectReason.ViewerBlocked || !outcome.listed) {
                null
            } else {
                val text = reason(outcome.reason)
                mentioned(mention, if (refundedPoints) "$text ${getString(Res.string.chat_refunded)}" else text)
            }
        }
        IntakeOutcome.Ignored, IntakeOutcome.Pending -> null
    }

    suspend fun usage(commandName: String, mention: String?): String =
        mentioned(mention, getString(Res.string.chat_usage, "!$commandName"))

    /** What a viewer below [permission] is told; null for [ChatPermission.Everyone]. */
    suspend fun denied(permission: ChatPermission, mention: String?): String? {
        val text = when (permission) {
            ChatPermission.Everyone -> return null
            ChatPermission.Followers -> getString(Res.string.chat_denied_followers)
            ChatPermission.Subscribers -> getString(Res.string.chat_denied_subscribers)
            ChatPermission.Vips -> getString(Res.string.chat_denied_vips)
            ChatPermission.Moderators -> getString(Res.string.chat_denied_moderators)
        }
        return mentioned(mention, text)
    }

    /** What plays now. While Spotify has nothing active the last-played card is not "playing". */
    suspend fun song(snapshot: PlaybackSnapshot, mention: String?): String {
        val now = snapshot.nowPlaying?.takeIf { it.kind == NowPlayingKind.Live }
            ?: return mentioned(mention, getString(Res.string.chat_song_nothing))
        val name = now.track.name
        val artists = artistsOf(now.track)
        val requester = now.request?.requester
        val text = when {
            requester != null -> getString(Res.string.chat_song_requested, name, artists, requester.displayName)
            now.requesterKnown -> getString(Res.string.chat_song_own_music, name, artists)
            else -> getString(Res.string.chat_song_plain, name, artists)
        }
        return mentioned(mention, text)
    }

    /**
     * The request handed over to Spotify, then the next [QUEUE_ITEMS] numbered, then where the asker's own requests
     * are. Never longer than [MAX_CHARS]: numbered items are dropped from the end, whole.
     */
    suspend fun queue(snapshot: PlaybackSnapshot, askerId: String?, mention: String?): String {
        val prefix = mention.orEmpty()
        val next = snapshot.handedOver?.let { getString(Res.string.chat_queue_next, it.track.name, artistsOf(it.track)) }
        val items = snapshot.queue.take(QUEUE_ITEMS).mapIndexed { i, r ->
            getString(Res.string.chat_queue_item, i + 1, r.track.name, artistsOf(r.track))
        }
        val own = snapshot.queue.withIndex()
            .filter { askerId != null && it.value.requester.twitchUserId == askerId }
            .map { "#${it.index + 1}" }
        val you = own.takeIf { it.isNotEmpty() }?.let { getString(Res.string.chat_queue_you, it.joinToString(", ")) }

        if (next == null && items.isEmpty()) return prefix + (you ?: getString(Res.string.chat_queue_empty))

        fun render(count: Int): String =
            listOfNotNull(next, items.take(count).joinToString(" ").ifEmpty { null }, you).joinToString("; ")

        var count = items.size
        while (count > 0 && (prefix + render(count)).length > MAX_CHARS) count--
        return prefix + render(count)
    }

    suspend fun wrongSong(removed: Request?, mention: String?): String = mentioned(
        mention,
        if (removed == null) {
            getString(Res.string.chat_wrongsong_none)
        } else {
            getString(Res.string.chat_wrongsong_removed, removed.track.name, artistsOf(removed.track))
        },
    )

    /** A moderator's `!skip`: the song that was playing. */
    suspend fun skipped(track: Track, mention: String?): String =
        mentioned(mention, getString(Res.string.chat_skipped, track.name, artistsOf(track)))

    /** A moderator's `!blacklist`: the song that was blocked and skipped. */
    suspend fun blacklisted(track: Track, mention: String?): String =
        mentioned(mention, getString(Res.string.chat_blacklisted, track.name, artistsOf(track)))

    /**
     * A moderator's `!remove`: the removed request, or "No position n". Without a number ([position] null) it says how
     * the command, configured as [commandName], is used.
     */
    suspend fun removed(removed: Request?, position: Int?, commandName: String, mention: String?): String = mentioned(
        mention,
        when {
            removed != null -> getString(Res.string.chat_wrongsong_removed, removed.track.name, artistsOf(removed.track))
            position == null -> getString(Res.string.chat_remove_usage, "!$commandName")
            else -> getString(Res.string.chat_remove_none, position.toString())
        },
    )

    private suspend fun accepted(outcome: IntakeOutcome.Accepted): String {
        val track = outcome.request.track
        val name = track.name
        val artists = artistsOf(track)
        if (outcome.position <= 0) return getString(Res.string.chat_already_next, name, artists)
        val minutes = ceil(outcome.waitMs / 60_000.0).toInt()
        return if (minutes < 1) {
            getString(Res.string.chat_added_soon, name, artists, outcome.position)
        } else {
            getString(Res.string.chat_added, name, artists, outcome.position, minutes)
        }
    }

    private suspend fun reason(reason: RejectReason): String = when (reason) {
        is RejectReason.NotATrack -> kindName(reason.kind)
            ?.let { getString(Res.string.chat_rejected_not_a_track, it) }
            ?: getString(Res.string.chat_rejected_not_a_track_generic)
        RejectReason.NoMatch -> getString(Res.string.chat_rejected_no_match)
        RejectReason.Unplayable -> getString(Res.string.chat_rejected_unplayable)
        RejectReason.SpotifyUnavailable -> getString(Res.string.chat_rejected_spotify_unavailable)
        RejectReason.ViewerBlocked -> ""
        RejectReason.TrackBlocked -> getString(Res.string.chat_rejected_track_blocked)
        RejectReason.ArtistBlocked -> getString(Res.string.chat_rejected_artist_blocked)
        RejectReason.Explicit -> getString(Res.string.chat_rejected_explicit)
        is RejectReason.TooShort -> getString(Res.string.chat_rejected_too_short, minSec(reason.minMs))
        is RejectReason.TooLong -> getString(Res.string.chat_rejected_too_long, minSec(reason.maxMs))
        RejectReason.Closed -> getString(Res.string.chat_rejected_closed)
        is RejectReason.QueueFull -> getString(Res.string.chat_rejected_queue_full, reason.max)
        is RejectReason.ViewerLimit -> getString(Res.string.chat_rejected_viewer_limit, reason.max)
        is RejectReason.Cooldown -> getString(Res.string.chat_rejected_cooldown, minSec(reason.remainingMs))
        RejectReason.Duplicate -> getString(Res.string.chat_rejected_duplicate)
        is RejectReason.BelowMinimum -> getString(Res.string.chat_rejected_below_minimum, reason.minBits)
    }

    private suspend fun kindName(kind: String): String? = when (kind) {
        "album" -> getString(Res.string.spotify_kind_album)
        "playlist" -> getString(Res.string.spotify_kind_playlist)
        "artist" -> getString(Res.string.spotify_kind_artist)
        "episode" -> getString(Res.string.spotify_kind_episode)
        "show" -> getString(Res.string.spotify_kind_show)
        else -> null
    }

    /** "m:ss", rounded up to the second. */
    private fun minSec(ms: Long): String {
        val seconds = ceil(ms.coerceAtLeast(0) / 1000.0).toLong()
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    private fun artistsOf(track: Track) = track.artists.joinToString(", ")

    private fun mentioned(mention: String?, text: String) = mention.orEmpty() + text

    private companion object {
        const val QUEUE_ITEMS = 5
        const val MAX_CHARS = 500
    }
}
