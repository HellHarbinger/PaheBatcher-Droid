package com.pahebatcher.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The slider commit point — a drag updates ONLY
 * local gesture state and [SliderGesture.release] hands the final dragged
 * value to the ViewModel exactly once, so one gesture is ONE DataStore write
 * (never one per tick).
 *
 * The Compose wiring around the class (remember + `onValueChangeFinished`) is
 * covered on device by `SettingsContentTest`; this suite pins the commit
 * contract itself, which is the unit the decision makes testable.
 */
class SliderGestureTest {

    @Test
    fun `dragging writes nothing until release commits the dragged value once`() {
        val writes = mutableListOf<Float>()
        val gesture = SliderGesture(initial = 8f, persist = { writes += it })

        gesture.drag(12f)
        gesture.drag(20f)

        assertEquals("a drag never persists mid-gesture", emptyList<Float>(), writes)
        assertEquals("the thumb follows the drag locally", 20f, gesture.value, 0f)

        gesture.release()

        assertEquals(
            "exactly one write per gesture, carrying the dragged value",
            listOf(20f),
            writes,
        )
    }

    @Test
    fun `each release is an independent commit of its own gesture`() {
        val writes = mutableListOf<Float>()
        val gesture = SliderGesture(initial = 0f, persist = writes::add)

        gesture.drag(42f)
        gesture.release()
        gesture.drag(100f)
        gesture.release()

        assertEquals("one write per gesture", listOf(42f, 100f), writes)
    }

    @Test
    fun `the gesture starts from the committed value`() {
        val gesture = SliderGesture(initial = 240f, persist = {})

        assertEquals("seeds from the ViewModel's committed value", 240f, gesture.value, 0f)
    }
}
