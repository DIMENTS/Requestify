package nl.diments.requestify.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SaveHealthTest {
    @Test
    fun problemsOfEveryFileAreCollected() = runTest {
        val settings = MutableStateFlow<SaveProblem?>(null)
        val blacklist = MutableStateFlow<SaveProblem?>(null)
        val queue = MutableStateFlow<String?>(null)
        val health = SaveHealth(listOf(settings, blacklist), queue, backgroundScope, clock = { testScheduler.currentTime })
        runCurrent()
        assertEquals(emptyList(), health.problems.value)

        settings.value = SaveProblem("settings.json", "disk full", 0)
        advanceTimeBy(2_000)
        queue.value = "java.io.IOException: disk full"
        runCurrent()
        assertEquals(
            listOf(SaveProblem("settings.json", "disk full", 0), SaveProblem("queue.json", "java.io.IOException: disk full", 2_000)),
            health.problems.value,
        )

        advanceTimeBy(1_000)
        queue.value = "java.io.IOException: still full"
        runCurrent()
        assertEquals(2_000, health.problems.value.last().sinceEpochMs, "the queue's outage keeps its start")

        settings.value = null
        queue.value = null
        runCurrent()
        assertEquals(emptyList(), health.problems.value)
    }
}
