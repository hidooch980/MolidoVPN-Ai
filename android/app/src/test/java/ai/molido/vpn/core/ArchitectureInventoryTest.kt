package ai.molido.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchitectureInventoryTest {
    @Test
    fun containsExactlySixEngineSlots() {
        assertEquals(6, ArchitectureInventory.engines.size)
    }

    @Test
    fun containsExactlyEightComplementaryCores() {
        assertEquals(8, ArchitectureInventory.cores.size)
    }
}
