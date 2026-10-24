package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test

class PetInteractionTest {
    @Test fun earlyDragCannotLaterStartRecording() {
        val press = PetPressState(8f)
        assertEquals(PetPressPhase.WAITING, press.move(3f, 4f))
        assertEquals(PetPressPhase.DRAGGING, press.move(9f, 0f))
        assertEquals(PetPressPhase.DRAGGING, press.hold())
    }

    @Test fun movementAfterLongPressCancelsVoiceInsteadOfMovingPet() {
        val press = PetPressState(8f)
        assertEquals(PetPressPhase.RECORDING, press.hold())
        assertEquals(PetPressPhase.RECORDING, press.move(60f, -100f))
        assertTrue(PetInteractionRules.cancelVoice(30f, -1f, 60f, 60f))
        assertTrue(PetInteractionRules.cancelVoice(60f, 30f, 60f, 60f))
        assertFalse(PetInteractionRules.cancelVoice(30f, 30f, 60f, 60f))
    }

    @Test fun everyActiveInteractionPausesTheIdleHideTimer() {
        assertTrue(PetInteractionRules.canAutoHide(true, false, false, false, false, false))
        assertFalse(PetInteractionRules.canAutoHide(false, false, false, false, false, false))
        assertFalse(PetInteractionRules.canAutoHide(true, true, false, false, false, false))
        assertFalse(PetInteractionRules.canAutoHide(true, false, true, false, false, false))
        assertFalse(PetInteractionRules.canAutoHide(true, false, false, true, false, false))
        assertFalse(PetInteractionRules.canAutoHide(true, false, false, false, true, false))
        assertFalse(PetInteractionRules.canAutoHide(true, false, false, false, false, true))
    }

    @Test fun hiddenPetIsOutsideWhileSproutTargetIsInsideEveryEdge() {
        val width = 400f
        val height = 800f
        val pet = 60f
        val touch = 44f
        for (edge in PetEdge.entries) for (ratio in listOf(0f, .5f, 1f)) {
            val x = PetDockGeometry.sproutX(edge, ratio, width, pet, touch)
            val y = PetDockGeometry.sproutY(edge, ratio, height, pet, touch)
            assertTrue(x >= 0 && x + touch <= width)
            assertTrue(y >= 0 && y + touch <= height)
            when (edge) {
                PetEdge.LEFT -> assertTrue(PetDockGeometry.hiddenX(edge, ratio, width, pet) + pet <= 0)
                PetEdge.RIGHT -> assertTrue(PetDockGeometry.hiddenX(edge, ratio, width, pet) >= width)
                PetEdge.TOP -> assertTrue(PetDockGeometry.hiddenY(edge, ratio, height, pet) + pet <= 0)
                PetEdge.BOTTOM -> assertTrue(PetDockGeometry.hiddenY(edge, ratio, height, pet) >= height)
            }
        }
    }
}
