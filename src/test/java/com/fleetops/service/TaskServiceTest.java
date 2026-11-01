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
    void createTask_productionLineNotFound_fail(){
        when(productionLineRepository.findById(ProductionLineType.LINE_A))
            .thenReturn(Optional.empty());
        
        assertThrows(
            ProductionLineNotFoundException.class,
            () -> taskService.createTask(ProductionLineType.LINE_A)
        );

        verify(taskRepository, never()).save(any(Task.class));
    }
}