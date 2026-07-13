package com.samnick.neverspiral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TonalBalanceEngineTest {

    @Test
    fun `starts at zero`() {
        val engine = TonalBalanceEngine(1)
        assertEquals(0f, engine.smoothedBands[0], 0.001f)
        assertEquals(0f, engine.referenceBands[0], 0.001f)
    }

    @Test
    fun `the fast tilt curve moves further than the slow reference for the same step`() {
        val engine = TonalBalanceEngine(1)
        // Tilt's 4s time constant is over 10x faster than the reference's 45s, so for the same
        // step the tilt curve should have closed much more of the distance to the target.
        engine.step(1f, floatArrayOf(1f))
        assertTrue(
            "tilt=${engine.smoothedBands[0]} reference=${engine.referenceBands[0]}",
            engine.smoothedBands[0] > engine.referenceBands[0],
        )
    }

    @Test
    fun `reset clears both curves`() {
        val engine = TonalBalanceEngine(2)
        engine.step(1f, floatArrayOf(1f, 1f))
        engine.reset()
        assertTrue(engine.smoothedBands.all { it == 0f })
        assertTrue(engine.referenceBands.all { it == 0f })
    }
}
