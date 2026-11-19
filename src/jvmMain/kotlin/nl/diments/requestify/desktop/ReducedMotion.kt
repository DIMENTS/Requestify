package nl.diments.requestify.desktop

import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary

/**
 * Windows' "Animation effects" (Settings → Accessibility → Visual effects) are off. [read] answers whether they are on;
 * when Windows cannot say (or the call fails), Requestify animates as usual.
 */
fun windowsAnimationsOff(read: () -> Boolean? = ::readClientAreaAnimation): Boolean = try {
    read() == false
} catch (_: Throwable) {
    false
}

private const val SPI_GETCLIENTAREAANIMATION = 0x1042

/** Whether Windows animates inside windows (null: it did not say). */
private fun readClientAreaAnimation(): Boolean? {
    val value = IntByReference()
    return if (SpiApi.INSTANCE.SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, value, 0)) value.value != 0 else null
}

/** user32's SystemParametersInfoW, for the one setting Requestify reads. */
private interface SpiApi : StdCallLibrary {
    @Suppress("FunctionName")
    fun SystemParametersInfoW(uiAction: Int, uiParam: Int, pvParam: IntByReference, fWinIni: Int): Boolean

    companion object {
        val INSTANCE: SpiApi by lazy { Native.load("user32", SpiApi::class.java) }
    }
}
