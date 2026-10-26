package app.openbill.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class InitialWindowStateTest {
    @Test
    fun smallOrPortraitScreensStartMaximizedAtScreenSize() {
        // 3000x2120 phosh output at integer AWT scale 2
        val phosh = initialWindowState(Rectangle(0, 0, 1500, 1060))
        assertEquals(WindowPlacement.Maximized, phosh.placement)
        assertEquals(1500.dp, phosh.size.width)
        assertEquals(WindowPlacement.Maximized, initialWindowState(Rectangle(0, 0, 1080, 1920)).placement)
    }

    @Test
    fun largeLandscapeScreensGetPreferredFloatingWindow() {
        val s = initialWindowState(Rectangle(0, 0, 1920, 1080))
        assertEquals(WindowPlacement.Floating, s.placement)
        assertEquals(1100.dp, s.size.width)
    }
}
