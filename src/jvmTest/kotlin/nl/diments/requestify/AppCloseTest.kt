package nl.diments.requestify

import kotlin.test.Test
import kotlin.test.assertEquals

class AppCloseTest {
    @Test
    fun theWindowIsHiddenBeforeTheGracefulStopWaits() {
        val steps = mutableListOf<String>()
        closeApp(
            hide = { steps += "hide" },
            stopGracefully = { steps += "stop" },
            exit = { steps += "exit" },
        )
        assertEquals(listOf("hide", "stop", "exit"), steps, "a closing window must not hang on screen while Twitch answers")
    }
}
