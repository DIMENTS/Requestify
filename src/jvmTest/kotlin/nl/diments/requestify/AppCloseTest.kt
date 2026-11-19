package nl.diments.requestify

import kotlin.test.Test
import kotlin.test.assertEquals

class AppCloseTest {
    @Test
    fun quitHidesStopsMarksAndExitsInOrder() {
        val steps = mutableListOf<String>()
        quitApp(
            markQuitting = { steps += "quitting" },
            hideTray = { steps += "hide tray" },
            hideWindow = { steps += "hide window" },
            stopGracefully = { steps += "stop" },
            markOrderlyEnd = { steps += "orderly end" },
            exit = { steps += "exit" },
        )
        assertEquals(
            listOf("quitting", "hide tray", "hide window", "stop", "orderly end", "exit"),
            steps,
            "nothing hangs on screen while Twitch answers, and the next start knows this was no crash",
        )
    }
}
