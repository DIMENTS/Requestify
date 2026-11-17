package nl.diments.requestify.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import nl.diments.requestify.core.model.ChannelPointsRef

class ChannelPointsRefTest {
    @Test
    fun roundTripsBothKinds() {
        val managed = ChannelPointsRef("rw1", "rd1", managed = true)
        val linked = ChannelPointsRef("rw1", "rd1", managed = false)
        assertEquals("cp:rw1:rd1", managed.format())
        assertEquals("cpx:rw1:rd1", linked.format())
        assertEquals(managed, ChannelPointsRef.parse("cp:rw1:rd1"))
        assertEquals(linked, ChannelPointsRef.parse("cpx:rw1:rd1"))
    }

    @Test
    fun otherRefsAreNotChannelPoints() {
        assertNull(ChannelPointsRef.parse(null))
        assertNull(ChannelPointsRef.parse("cheer-1"))
        assertNull(ChannelPointsRef.parse("cp:only"))
        assertNull(ChannelPointsRef.parse("cp::x"))
    }
}
