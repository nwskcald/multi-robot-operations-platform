package com.fleetops.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductionLineTest {
    @Test void releaseComparesIdsByValue() {
        var line = new ProductionLine(ProductionLineType.LINE_A);
        line.occupy(Long.valueOf("1000"));
        line.release(Long.valueOf("1000"));
        assertEquals(ProductionLineStatus.AVAILABLE, line.getStatus());
        assertNull(line.getOccupyingRobotId());
    }
    @Test void invalidReleasePreservesOccupancy() {
        var line = new ProductionLine(ProductionLineType.LINE_A);
        assertThrows(IllegalStateException.class, () -> line.release(1L));
        assertThrows(IllegalArgumentException.class, () -> line.occupy(null));
        line.occupy(1L);
        assertThrows(IllegalStateException.class, () -> line.release(null));
        assertThrows(IllegalStateException.class, () -> line.release(2L));
        assertEquals(1L, line.getOccupyingRobotId());
    }
}
