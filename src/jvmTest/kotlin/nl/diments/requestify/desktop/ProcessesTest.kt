package nl.diments.requestify.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessesTest {
    @Test
    fun aDetachedProcessInheritsNoStdio() {
        val builder = detachedProcess(listOf("C:\\Program Files\\Requestify\\Requestify.exe", "--after-crash"))
        assertEquals(listOf("C:\\Program Files\\Requestify\\Requestify.exe", "--after-crash"), builder.command())
        assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectOutput(), "no pipe back to a parent that is about to die")
        assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectError())
        assertEquals(File("NUL"), builder.redirectInput().file(), "and nothing to read from it")
    }
}
