package nl.diments.requestify.core.intake

import nl.diments.requestify.core.spotify.ShortLinkAnswer
import nl.diments.requestify.core.spotify.ShortLinkResolver

/** A scriptable [ShortLinkResolver] that records the addresses it is asked; one not listed is unresolvable. */
class FakeShortLinks : ShortLinkResolver {
    val answers = mutableMapOf<String, ShortLinkAnswer>()
    val calls = mutableListOf<String>()

    /** [url] redirects to [target]. */
    fun leadsTo(url: String, target: String) {
        answers[url] = ShortLinkAnswer.LeadsTo(target)
    }

    override suspend fun resolve(url: String): ShortLinkAnswer {
        calls += url
        return answers[url] ?: ShortLinkAnswer.Unresolvable
    }
}
