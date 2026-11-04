package com.fleetops.integration;

import com.fleetops.repository.*;
import com.fleetops.service.*;
import com.fleetops.domain.*;
import com.fleetops.exception.ProductionLineNotFoundException;

import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import static org.junit.jupiter.api.Assertions.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test") 
@Transactional
class TaskIntegrationTest{

    @Autowired 
    private TaskRepository taskRepository;

    @Autowired 
    private ProductionLineRepository productionLineRepository;

    @Autowired
    private TaskService taskService;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void createTask_success(){
        ProductionLine line = new ProductionLine(ProductionLineType.LINE_A);
        productionLineRepository.save(line);
        Task createdTask = taskService.createTask(ProductionLineType.LINE_A);

        assertNotNull(createdTask.getId());
        assertEquals(TaskStatus.WAITING, createdTask.getStatus());
        assertNull(createdTask.getRobot());

        entityManager.flush();
        entityManager.clear();

        Task foundTask = taskRepository.findById(createdTask.getId())
            .orElseThrow();
        
        assertEquals(TaskStatus.WAITING, foundTask.getStatus());
        assertNull(foundTask.getRobot());
        assertEquals(
            ProductionLineType.LINE_A,
            foundTask.getProductionLine().getType()
        );
    }

    @Test 
    void createTask_productionLineNotFound_fail(){
        long beforeCount = taskRepository.count();

        assertThrows(
            ProductionLineNotFoundException.class,
            () -> taskService.createTask(ProductionLineType.LINE_A)
        );

        assertEquals(beforeCount, taskRepository.count());
    }
}