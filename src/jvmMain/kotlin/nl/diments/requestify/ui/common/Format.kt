package nl.diments.requestify.ui.common

import androidx.compose.runtime.Composable
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.requester_you
import nl.diments.requestify.resources.source_bits
import nl.diments.requestify.resources.source_channel_points
import nl.diments.requestify.resources.source_chat
import nl.diments.requestify.resources.source_donolink
import nl.diments.requestify.resources.source_manual
import nl.diments.requestify.resources.source_streamelements
import org.jetbrains.compose.resources.stringResource

/** "m:ss", rounded down; minutes keep counting past an hour (3 600 000 → "60:00"). */
fun formatDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** Currency symbol + amount: no decimals when whole ("€5"), else two with the [locale]'s separator ("€2,50"). */
fun formatMoney(amountCents: Long, currency: String, locale: Locale): String {
    val symbol = runCatching { Currency.getInstance(currency).getSymbol(locale) }.getOrDefault("$currency ")
    val digits = if (amountCents % 100 == 0L) 0 else 2
    val number = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = digits
        maximumFractionDigits = digits
    }
    return symbol + number.format(BigDecimal.valueOf(amountCents, 2))
}

/** The text on a request's source chip, e.g. "!sr", "500 Bits" or "DonoLink €5". */
@Composable
fun sourceLabel(request: Request): String = sourceLabel(request.source, request.payment)

/** The same, for a [source] and [payment] that are not (yet) a [Request], like a rejected one. */
@Composable
fun sourceLabel(source: RequestSource, payment: Payment?): String = when (source) {
    RequestSource.MANUAL -> stringResource(Res.string.source_manual)
    RequestSource.CHAT -> stringResource(Res.string.source_chat)
    RequestSource.CHANNEL_POINTS -> stringResource(Res.string.source_channel_points)
    RequestSource.BITS -> stringResource(Res.string.source_bits, (payment as? Payment.Bits)?.count ?: 0)
    RequestSource.STREAMELEMENTS -> stringResource(Res.string.source_streamelements, moneyOf(payment)).trim()
    RequestSource.DONOLINK -> stringResource(Res.string.source_donolink, moneyOf(payment)).trim()
}

/** Who asked: the viewer's name, or "You" for a track the streamer added. */
@Composable
fun requesterLabel(request: Request): String = requesterLabel(request.source, request.requester)

@Composable
fun requesterLabel(source: RequestSource, requester: Requester): String =
    if (source == RequestSource.MANUAL) {
        stringResource(Res.string.requester_you)
    } else {
        requester.displayName.ifBlank { requester.login.orEmpty() }
    }

private fun moneyOf(payment: Payment?): String =
    (payment as? Payment.Money)?.let { formatMoney(it.amountCents, it.currency, Locale.getDefault()) }.orEmpty()
