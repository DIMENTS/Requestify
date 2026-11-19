package nl.diments.requestify.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class ReducedMotionTest {
    @Test
    fun aFailingCallMeansAnimationsOn() {
        assertEquals(false, windowsAnimationsOff(read = { null }), "Windows did not say: animate as usual")
        assertEquals(false, windowsAnimationsOff(read = { throw UnsatisfiedLinkError("no user32") }))
    }

    @Test
    fun windowsSettingIsFollowed() {
        assertEquals(true, windowsAnimationsOff(read = { false }), "Settings → Accessibility → Animation effects off")
        assertEquals(false, windowsAnimationsOff(read = { true }))
    }
}
