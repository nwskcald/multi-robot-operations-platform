package com.fleetops.service;

import com.fleetops.domain.*;
import com.fleetops.repository.RobotRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RobotServiceTest {

    @Mock
    private RobotRepository robotRepository;

    @InjectMocks
    private RobotService robotService;

    @Test
    void reportArrival_wrongTaskId() {
        // Given
        Robot robot = Robot.register();
        ProductionLine line =
                new ProductionLine(ProductionLineType.LINE_A);
        Task task = new Task(line);
        org.springframework.test.util.ReflectionTestUtils.setField(task, "id", 2L);

        task.assignTo(robot);
        robot.startDelivery(task);

        Long robotId = 1L;
        Long wrongTaskId = 99L;

        when(robotRepository.findByIdForUpdate(robotId))
                .thenReturn(Optional.of(robot));

        // When & Then
        assertThrows(
                IllegalStateException.class,
                () -> robotService.reportArrival(wrongTaskId, robotId)
        );

        assertEquals(
                RobotOperationStatus.DELIVERING,
                robot.getOperationStatus()
        );

        assertEquals(
                TaskStatus.ASSIGNED,
                task.getStatus()
        );

        verify(robotRepository, times(1))
                .findByIdForUpdate(robotId);
    }
}