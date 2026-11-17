package nl.diments.requestify.core.twitch

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.diments.requestify.core.auth.TokenResult
import nl.diments.requestify.core.twitch.auth.TWITCH_CLIENT_ID

class TwitchApiTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/twitch/$name")) { "missing fixture $name" }
            .use { it.readBytes().decodeToString() }

    private fun MockRequestHandleScope.fixtureOk(name: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(fixture(name), status, json)

    private fun api(
        token: suspend (Boolean) -> TokenResult = { TokenResult.Ok("tok") },
        broadcasterId: String? = "141981764",
        requestTimeoutMs: Long = 15_000,
        nowMs: () -> Long = System::currentTimeMillis,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = TwitchApi(
        http = HttpClient(MockEngine(handler)),
        token = token,
        broadcasterId = { broadcasterId },
        baseUrl = "https://twitch.test/helix",
        requestTimeoutMs = requestTimeoutMs,
        nowMs = nowMs,
    )

    private fun <T> TwitchResult<T>.ok(): T = assertIs<TwitchResult.Ok<T>>(this).value

    private fun HttpRequestData.bodyJson(): JsonObject =
        Json.parseToJsonElement((body as TextContent).text).jsonObject

    private fun HttpRequestData.assertAuth() {
        assertEquals(TWITCH_CLIENT_ID, headers["Client-Id"])
        assertEquals("Bearer tok", headers[HttpHeaders.Authorization])
    }

    @Test
    fun meReadsTheTokenUser() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        val me = api { req -> seen = req; fixtureOk("users-me.json") }.me().ok()
        assertEquals(TwitchChannel("141981764", "diments", "DIMENTS", "affiliate"), me)
        assertEquals(HttpMethod.Get, seen!!.method)
        assertEquals("/helix/users", seen!!.url.encodedPath)
        assertEquals("", seen!!.url.encodedQuery)
        seen!!.assertAuth()
    }

    @Test
    fun meWithoutUserDataFails() = runBlocking<Unit> {
        assertIs<TwitchResult.Failed>(api { fixtureOk("users-empty.json") }.me())
    }

    @Test
    fun userByLoginFindsAndMisses() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        val ref = api { req -> seen = req; fixtureOk("users-login.json") }.userByLogin("Viewer_One").ok()
        assertEquals(TwitchUserRef("99", "viewer_one", "Viewer_One"), ref)
        assertEquals("/helix/users", seen!!.url.encodedPath)
        assertEquals("Viewer_One", seen!!.url.parameters["login"])
        seen!!.assertAuth()
        assertNull(api { fixtureOk("users-empty.json") }.userByLogin("nobody").ok())
    }

    @Test
    fun isLiveQueriesStreamsForTheBroadcaster() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        assertTrue(api { req -> seen = req; fixtureOk("streams-live.json") }.isLive().ok())
        assertEquals("/helix/streams", seen!!.url.encodedPath)
        assertEquals("141981764", seen!!.url.parameters["user_id"])
        seen!!.assertAuth()
        assertEquals(false, api { fixtureOk("streams-offline.json") }.isLive().ok())
    }

    @Test
    fun followsQueriesChannelFollowers() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        assertTrue(api { req -> seen = req; fixtureOk("followers-yes.json") }.follows("99").ok())
        assertEquals("/helix/channels/followers", seen!!.url.encodedPath)
        assertEquals("141981764", seen!!.url.parameters["broadcaster_id"])
        assertEquals("99", seen!!.url.parameters["user_id"])
        seen!!.assertAuth()
    }

    @Test
    fun followersEmptyDataMeansNotFollowing() = runBlocking<Unit> {
        assertEquals(false, api { fixtureOk("followers-empty.json") }.follows("99").ok())
    }

    @Test
    fun sendChatPostsTheMessageAsTheBroadcaster() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        val sent = api { req -> seen = req; fixtureOk("chat-sent.json") }.sendChat("hello", replyTo = "m-1").ok()
        assertEquals(SentChat("abc-123", true), sent)
        assertEquals(HttpMethod.Post, seen!!.method)
        assertEquals("/helix/chat/messages", seen!!.url.encodedPath)
        seen!!.assertAuth()
        val body = seen!!.bodyJson()
        assertEquals("141981764", body["broadcaster_id"]!!.jsonPrimitive.content)
        assertEquals("141981764", body["sender_id"]!!.jsonPrimitive.content)
        assertEquals("hello", body["message"]!!.jsonPrimitive.content)
        assertEquals("m-1", body["reply_parent_message_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun sendChatOmitsReplyAndCutsLongMessages() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        api { req -> seen = req; fixtureOk("chat-sent.json") }.sendChat("x".repeat(600), replyTo = null).ok()
        val body = seen!!.bodyJson()
        assertEquals(500, body["message"]!!.jsonPrimitive.content.length)
        assertTrue("reply_parent_message_id" !in body)
    }

    @Test
    fun sendChatReportsDroppedMessages() = runBlocking<Unit> {
        val sent = api { fixtureOk("chat-dropped.json") }.sendChat("hello", null).ok()
        assertEquals(SentChat("def-456", false), sent)
    }

    @Test
    fun rewardsListsAndFiltersManageable() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        val rewards = api { req -> seen = req; fixtureOk("rewards-list.json") }.rewards(onlyManageable = true).ok()
        assertEquals(
            listOf(
                Reward("rw-1", "Song request", 500, "Type a song", isEnabled = true, isPaused = false, isUserInputRequired = true, skipsRequestQueue = false),
                Reward("rw-2", "Hydrate", 100, "", isEnabled = false, isPaused = true, isUserInputRequired = false, skipsRequestQueue = true),
            ),
            rewards,
        )
        assertEquals("/helix/channel_points/custom_rewards", seen!!.url.encodedPath)
        assertEquals("141981764", seen!!.url.parameters["broadcaster_id"])
        assertEquals("true", seen!!.url.parameters["only_manageable_rewards"])
        seen!!.assertAuth()
        api { req -> seen = req; fixtureOk("rewards-list.json") }.rewards(onlyManageable = false).ok()
        assertNull(seen!!.url.parameters["only_manageable_rewards"])
    }

    @Test
    fun createRewardRequiresUserInputAndKeepsTheQueue() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        val reward = api { req -> seen = req; fixtureOk("reward.json") }
            .createReward(RewardSpec("Song request", 500, "Type a song")).ok()
        assertEquals("rw-1", reward.id)
        assertEquals(HttpMethod.Post, seen!!.method)
        assertEquals("/helix/channel_points/custom_rewards", seen!!.url.encodedPath)
        assertEquals("141981764", seen!!.url.parameters["broadcaster_id"])
        seen!!.assertAuth()
        val body = seen!!.bodyJson()
        assertEquals("Song request", body["title"]!!.jsonPrimitive.content)
        assertEquals(500, body["cost"]!!.jsonPrimitive.content.toInt())
        assertEquals("Type a song", body["prompt"]!!.jsonPrimitive.content)
        assertTrue(body["is_user_input_required"]!!.jsonPrimitive.boolean)
        assertEquals(false, body["should_redemptions_skip_request_queue"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun createRewardDuplicateTitleIsBadRequestWithMessage() = runBlocking<Unit> {
        val result = api { fixtureOk("reward-duplicate.json", HttpStatusCode.BadRequest) }
            .createReward(RewardSpec("Song request", 500, ""))
        assertEquals(TwitchResult.BadRequest("CREATE_CUSTOM_REWARD_DUPLICATE_REWARD"), result)
    }

    @Test
    fun nonAffiliateIs403Forbidden() = runBlocking<Unit> {
        val result = api { fixtureOk("reward-not-affiliate.json", HttpStatusCode.Forbidden) }
            .createReward(RewardSpec("Song request", 500, ""))
        assertEquals(TwitchResult.Forbidden("The broadcaster is not a partner or affiliate."), result)
    }

    @Test
    fun updateRewardPatchesOnlyTheGivenFields() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        val reward = api { req -> seen = req; fixtureOk("reward.json") }
            .updateReward("rw-1", RewardPatch(cost = 700, isPaused = true)).ok()
        assertEquals("rw-1", reward.id)
        assertEquals(HttpMethod.Patch, seen!!.method)
        assertEquals("/helix/channel_points/custom_rewards", seen!!.url.encodedPath)
        assertEquals("141981764", seen!!.url.parameters["broadcaster_id"])
        assertEquals("rw-1", seen!!.url.parameters["id"])
        seen!!.assertAuth()
        val body = seen!!.bodyJson()
        assertEquals(setOf("cost", "is_paused"), body.keys)
        assertEquals(JsonPrimitive(700), body["cost"])
        assertEquals(JsonPrimitive(true), body["is_paused"])
    }

    @Test
    fun unfulfilledRedemptionsFollowsPagination() = runBlocking<Unit> {
        val seen = mutableListOf<HttpRequestData>()
        val result = api { req ->
            seen += req
            if (req.url.parameters["after"] == null) fixtureOk("redemptions-page1.json") else fixtureOk("redemptions-page2.json")
        }.unfulfilledRedemptions("rw-1").ok()
        assertEquals(listOf("rd-1", "rd-2"), result.map { it.id })
        assertEquals(
            Redemption(
                "rd-1", "rw-1", "99", "viewer_one", "Viewer_One", "Daft Punk - Around the World",
                Instant.parse("2026-11-17T18:30:00.123456Z").toEpochMilli(),
            ),
            result[0],
        )
        assertEquals("", result[1].userInput)
        assertEquals(2, seen.size)
        seen.forEach {
            assertEquals("/helix/channel_points/custom_rewards/redemptions", it.url.encodedPath)
            assertEquals("141981764", it.url.parameters["broadcaster_id"])
            assertEquals("rw-1", it.url.parameters["reward_id"])
            assertEquals("UNFULFILLED", it.url.parameters["status"])
            assertEquals("50", it.url.parameters["first"])
            it.assertAuth()
        }
        assertEquals("CUR1", seen[1].url.parameters["after"])
    }

    @Test
    fun setRedemptionStatusPatchesWithStatusBody() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        api { req -> seen = req; fixtureOk("redemption-updated.json") }
            .setRedemptionStatus("rw-1", "rd-1", RedemptionStatus.FULFILLED).ok()
        assertEquals(HttpMethod.Patch, seen!!.method)
        assertEquals("/helix/channel_points/custom_rewards/redemptions", seen!!.url.encodedPath)
        assertEquals("rd-1", seen!!.url.parameters["id"])
        assertEquals("141981764", seen!!.url.parameters["broadcaster_id"])
        assertEquals("rw-1", seen!!.url.parameters["reward_id"])
        seen!!.assertAuth()
        assertEquals("""{"status":"FULFILLED"}""", (seen!!.body as TextContent).text)
        api { req -> seen = req; fixtureOk("redemption-updated.json") }
            .setRedemptionStatus("rw-1", "rd-1", RedemptionStatus.CANCELED).ok()
        assertEquals("""{"status":"CANCELED"}""", (seen!!.body as TextContent).text)
    }

    @Test
    fun redemptionStatus404IsNotFound() = runBlocking<Unit> {
        val result = api { respond("""{"status":404,"message":"none"}""", HttpStatusCode.NotFound, json) }
            .setRedemptionStatus("rw-1", "rd-1", RedemptionStatus.FULFILLED)
        assertEquals(TwitchResult.NotFound, result)
    }

    @Test
    fun subscribePostsTheWebsocketTransport() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        api { req -> seen = req; fixtureOk("subscribe-202.json", HttpStatusCode.Accepted) }
            .subscribe("channel.chat.message", "1", mapOf("broadcaster_user_id" to "1", "user_id" to "1"), "sess-1").ok()
        assertEquals(HttpMethod.Post, seen!!.method)
        assertEquals("/helix/eventsub/subscriptions", seen!!.url.encodedPath)
        seen!!.assertAuth()
        val body = seen!!.bodyJson()
        assertEquals("channel.chat.message", body["type"]!!.jsonPrimitive.content)
        assertEquals("1", body["version"]!!.jsonPrimitive.content)
        assertEquals("1", body["condition"]!!.jsonObject["user_id"]!!.jsonPrimitive.content)
        val transport = body["transport"]!!.jsonObject
        assertEquals("websocket", transport["method"]!!.jsonPrimitive.content)
        assertEquals("sess-1", transport["session_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun subscribeTreats202And409AsOk() = runBlocking<Unit> {
        api { fixtureOk("subscribe-202.json", HttpStatusCode.Accepted) }.subscribe("t", "1", emptyMap(), "s").ok()
        api { fixtureOk("subscribe-409.json", HttpStatusCode.Conflict) }.subscribe("t", "1", emptyMap(), "s").ok()
    }

    @Test
    fun conflictOutsideSubscribeIsFailed() = runBlocking<Unit> {
        assertIs<TwitchResult.Failed>(api { fixtureOk("subscribe-409.json", HttpStatusCode.Conflict) }.isLive())
    }

    @Test
    fun retriesOnceAfter401WithForcedRefresh() = runBlocking<Unit> {
        val forced = mutableListOf<Boolean>()
        val bearers = mutableListOf<String?>()
        val result = api(token = { force -> forced += force; TokenResult.Ok(if (force) "fresh" else "old") }) { req ->
            bearers += req.headers[HttpHeaders.Authorization]
            if (bearers.size == 1) respond("", HttpStatusCode.Unauthorized) else fixtureOk("streams-live.json")
        }.isLive()
        assertTrue(result.ok())
        assertEquals(listOf(false, true), forced)
        assertEquals(listOf<String?>("Bearer old", "Bearer fresh"), bearers)
    }

    @Test
    fun secondUnauthorizedIsLoginNeeded() = runBlocking<Unit> {
        var calls = 0
        val result = api { calls++; respond("", HttpStatusCode.Unauthorized) }.isLive()
        assertEquals(TwitchResult.LoginNeeded, result)
        assertEquals(2, calls)
    }

    @Test
    fun tokenAnswersMapToResults() = runBlocking<Unit> {
        assertEquals(TwitchResult.LoggedOut, api(token = { TokenResult.LoggedOut }) { error("no call") }.isLive())
        assertEquals(TwitchResult.LoginNeeded, api(token = { TokenResult.LoginNeeded }) { error("no call") }.isLive())
        assertIs<TwitchResult.Failed>(api(token = { TokenResult.Unavailable }) { error("no call") }.isLive())
    }

    @Test
    fun noBroadcasterIdIsLoggedOut() = runBlocking<Unit> {
        val api = api(broadcasterId = null) { error("no call") }
        assertEquals(TwitchResult.LoggedOut, api.isLive())
        assertEquals(TwitchResult.LoggedOut, api.follows("1"))
        assertEquals(TwitchResult.LoggedOut, api.sendChat("x", null))
        assertEquals(TwitchResult.LoggedOut, api.rewards(false))
    }

    @Test
    fun rateLimitedUsesRatelimitResetOrFiveSeconds() = runBlocking<Unit> {
        val result = api(nowMs = { 1_000_000L }) {
            respond("", HttpStatusCode.TooManyRequests, headersOf("Ratelimit-Reset", "1007"))
        }.isLive()
        assertEquals(TwitchResult.RateLimited(7000), result)
        assertEquals(TwitchResult.RateLimited(5000), api { respond("", HttpStatusCode.TooManyRequests) }.isLive())
        val past = api(nowMs = { 2_000_000L }) {
            respond("", HttpStatusCode.TooManyRequests, headersOf("Ratelimit-Reset", "1"))
        }.isLive()
        assertEquals(TwitchResult.RateLimited(0), past)
    }

    @Test
    fun serverErrorAndGarbageAreFailed() = runBlocking<Unit> {
        assertIs<TwitchResult.Failed>(api { respond("", HttpStatusCode.InternalServerError) }.isLive())
        assertIs<TwitchResult.Failed>(api { respond("not json", HttpStatusCode.OK, json) }.isLive())
    }

    @Test
    fun timeoutIsFailedAndCancellationPropagates() = runBlocking<Unit> {
        assertIs<TwitchResult.Failed>(api(requestTimeoutMs = 50) { awaitCancellation() }.isLive())
        val outcome = runCatching {
            runBlocking { api { throw CancellationException("cancelled") }.isLive() }
        }
        assertIs<CancellationException>(outcome.exceptionOrNull())
    }
}
