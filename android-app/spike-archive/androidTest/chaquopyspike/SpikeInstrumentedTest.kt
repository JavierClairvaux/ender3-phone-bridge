package com.example.chaquopyspike

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpikeInstrumentedTest {
    @Test
    fun allSpikeChecksPass() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val results = Spike.runAll(ctx)
        val failed = results.filter { !it.second.startsWith("PASS") }
        assertTrue("failed: $failed", failed.isEmpty())
        assertTrue(results.size == 8)
    }
}
