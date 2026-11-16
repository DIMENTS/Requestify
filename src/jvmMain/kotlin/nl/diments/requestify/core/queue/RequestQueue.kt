package nl.diments.requestify.core.queue

import kotlinx.serialization.Serializable
import nl.diments.requestify.core.model.Request

@Serializable
data class RequestQueue(val items: List<Request> = emptyList()) {
    val head: Request? get() = items.firstOrNull()
    val totalDurationMs: Long get() = items.sumOf { it.track.durationMs }

    /** Free requests append. With [paidPriority], a paid one goes right after the last paid request (or at 0). */
    fun add(request: Request, paidPriority: Boolean): RequestQueue {
        if (!paidPriority || !request.isPaid) return RequestQueue(items + request)
        val index = items.indexOfLast { it.isPaid } + 1
        return RequestQueue(items.toMutableList().apply { add(index, request) })
    }

    fun addFirst(request: Request): RequestQueue = RequestQueue(listOf(request) + items)

    fun remove(id: String): RequestQueue =
        if (items.none { it.id == id }) this else RequestQueue(items.filterNot { it.id == id })

    fun move(id: String, toIndex: Int): RequestQueue {
        val item = items.firstOrNull { it.id == id } ?: return this
        val rest = items.filterNot { it.id == id }
        return RequestQueue(rest.toMutableList().apply { add(toIndex.coerceIn(0, rest.size), item) })
    }
}
