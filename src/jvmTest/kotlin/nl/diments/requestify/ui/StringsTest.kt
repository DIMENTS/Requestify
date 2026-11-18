package nl.diments.requestify.ui

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The English and Dutch strings, as the source tree has them. */
class StringsTest {
    private fun strings(folder: String): Map<String, String> =
        Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(Path.of("src/commonMain/composeResources/$folder/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private val english = strings("values")
    private val dutch = strings("values-nl")

    private fun placeholders(text: String) = Regex("""%\d+\$[sd]""").findAll(text).map { it.value }.toSortedSet()

    @Test
    fun everyStringIsInBothLanguagesWithTheSamePlaceholders() {
        assertEquals(english.keys, dutch.keys)
        for ((key, text) in english) assertEquals(placeholders(text), placeholders(dutch.getValue(key)), key)
    }

    @Test
    fun dutchSaysKanaalpuntenThroughout() {
        val mixed = dutch.filterValues { "Channel Points" in it }
        assertTrue(mixed.isEmpty(), "Dutch text that says Channel Points: $mixed")
        assertEquals("Kanaalpunten", dutch["source_channel_points"], "the source chip too")
    }
}
