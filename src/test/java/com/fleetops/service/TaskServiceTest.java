package com.fleetops.service;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.junit.jupiter.api.Assertions.*;

import com.fleetops.domain.*;
import com.fleetops.exception.ProductionLineNotFoundException;
import com.fleetops.repository.*;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest{

    @Mock
    private ProductionLineRepository productionLineRepository;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private org.springframework.context.ApplicationEventPublisher events;

    @Mock
    private RobotRepository robotRepository;

    @InjectMocks
    private TaskService taskService;

    @Test 
    void createTask_success(){
        ProductionLine line = new ProductionLine(ProductionLineType.LINE_A);

        when(productionLineRepository.findById(ProductionLineType.LINE_A))
            .thenReturn(Optional.of(line));

        when(taskRepository.save(any(Task.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        
        Task task = taskService.createTask(ProductionLineType.LINE_A);

        assertEquals(TaskStatus.WAITING, task.getStatus());
        assertNull(task.getRobot());
        assertSame(line, task.getProductionLine());
        verify(productionLineRepository).findById(ProductionLineType.LINE_A);
        verify(taskRepository).save(same(task));
    }

    @Test 
    void createTask_productionLineNotFound_throwsException(){
        when(productionLineRepository.findById(ProductionLineType.LINE_A))
            .thenReturn(Optional.empty());
        
        assertThrows(
            ProductionLineNotFoundException.class,
            () -> taskService.createTask(ProductionLineType.LINE_A)
        );

        verify(taskRepository, never()).save(any(Task.class));
    }
    @Test
    void assignmentPublishesCommandDataAfterDomainTransitions() {
        Task task = new Task(new ProductionLine(ProductionLineType.LINE_A));
        Robot robot = Robot.register();
        when(taskRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(task));
        when(robotRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(robot));

        taskService.assignTaskToRobot(2L, 1L);

        assertEquals(TaskStatus.ASSIGNED, task.getStatus());
        assertEquals(RobotOperationStatus.DELIVERING, robot.getOperationStatus());
        verify(events).publishEvent(new com.fleetops.command.TaskAssignedEvent(1L, 2L, 5));
    }

    @Test
    void invalidAssignmentDoesNotPublishCommand() {
        Task task = new Task(new ProductionLine(ProductionLineType.LINE_A));
        Robot robot = Robot.register();
        robot.disconnect();
        when(taskRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(task));
        when(robotRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(robot));

        assertThrows(IllegalStateException.class, () -> taskService.assignTaskToRobot(2L, 1L));
        verifyNoInteractions(events);
    }
}