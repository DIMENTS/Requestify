package nl.diments.requestify.ui.common

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test fun durationIsMinutesAndSeconds() = assertEquals("3:20", formatDuration(200_000))

    @Test fun durationRoundsDown() = assertEquals("0:59", formatDuration(59_999))

    @Test fun durationKeepsCountingMinutesPastAnHour() = assertEquals("60:00", formatDuration(3_600_000))

    @Test fun wholeMoneyHasNoDecimals() = assertEquals("€5", formatMoney(500, "EUR", Locale.ENGLISH))

    @Test fun moneyWithCentsUsesTheLocaleSeparator() {
        assertEquals("€2.50", formatMoney(250, "EUR", Locale.ENGLISH))
        assertEquals("€2,50", formatMoney(250, "EUR", Locale.forLanguageTag("nl")))
    }
}
