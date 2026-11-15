package com.fleetops.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductionLineTest {

    @Test
    void release_sameRobotIdValue_success() {
        // Given
        ProductionLine line = new ProductionLine(ProductionLineType.LINE_A);
        // Long 캐시 범위 밖의 값을 각각 생성해 객체가 아닌 값으로 비교하는지 확인한다.
        Long occupyingRobotId = Long.valueOf("1000");
        Long completingRobotId = Long.valueOf("1000");
        assertNotSame(occupyingRobotId, completingRobotId);
        line.occupy(occupyingRobotId);

        // When
        line.release(completingRobotId);

        // Then
        assertEquals(ProductionLineStatus.AVAILABLE, line.getStatus());
        assertNull(line.getOccupyingRobotId());
    }

    @Test
    void release_availableLine_throwsException() {
        // Given
        ProductionLine line = new ProductionLine(ProductionLineType.LINE_A);

        // When & Then
        assertThrows(IllegalStateException.class, () -> line.release(1L));
        assertEquals(ProductionLineStatus.AVAILABLE, line.getStatus());
        assertNull(line.getOccupyingRobotId());
    }

    @Test
    void occupy_nullRobotId_throwsException() {
        // Given
        ProductionLine line = new ProductionLine(ProductionLineType.LINE_A);

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> line.occupy(null));
        assertEquals(ProductionLineStatus.AVAILABLE, line.getStatus());
        assertNull(line.getOccupyingRobotId());
    }

    @Test
    void release_nullRobotId_throwsException() {
        // Given
        ProductionLine line = new ProductionLine(ProductionLineType.LINE_A);
        line.occupy(1L);

        // When & Then
        assertThrows(IllegalStateException.class, () -> line.release(null));
        assertEquals(ProductionLineStatus.OCCUPIED, line.getStatus());
        assertEquals(1L, line.getOccupyingRobotId());
    }

    @Test
    void release_anotherRobotId_throwsException() {
        // Given
        ProductionLine line = new ProductionLine(ProductionLineType.LINE_A);
        line.occupy(1L);

        // When & Then
        assertThrows(
            IllegalStateException.class, 
            () -> line.release(2L)
        );
        assertEquals(ProductionLineStatus.OCCUPIED, line.getStatus());
        assertEquals(1L, line.getOccupyingRobotId());
    }
}
