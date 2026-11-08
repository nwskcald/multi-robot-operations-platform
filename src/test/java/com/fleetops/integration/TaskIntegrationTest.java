package com.fleetops.integration;

import com.fleetops.repository.*;
import com.fleetops.service.*;
import com.fleetops.domain.*;
import com.fleetops.exception.ProductionLineNotFoundException;
import com.fleetops.exception.RobotNotFoundException;
import com.fleetops.exception.TaskNotFoundException;

import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import static org.junit.jupiter.api.Assertions.*;

import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Sql(
    scripts = "/cleanup.sql",
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD,
    config = @SqlConfig(
        transactionMode = SqlConfig.TransactionMode.ISOLATED
    )
)
class TaskIntegrationTest{

    @Autowired 
    private TaskRepository taskRepository;

    @Autowired 
    private RobotRepository robotRepository;

    @Autowired 
    private ProductionLineRepository productionLineRepository;

    @Autowired
    private TaskService taskService;

    @Autowired 
    private RobotService robotService;

    @PersistenceContext
    private EntityManager entityManager;

    private Robot registerRobot(){
        return robotService.registerRobot();
    }

    private Task createTask(ProductionLineType type){
        productionLineRepository.findById(type)
            .orElseGet(
                () -> productionLineRepository.save(
                    new ProductionLine(type)
                )
            );
        
        return taskService.createTask(type);
    }


    @Test
    @Transactional
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
    @Transactional 
    void createTask_productionLineNotFound_throwsException(){
        assertFalse(
            productionLineRepository.existsById(ProductionLineType.LINE_A)
        );

        assertThrows(
            ProductionLineNotFoundException.class,
            () -> taskService.createTask(ProductionLineType.LINE_A)
        );
    }

    @Test 
    @Transactional 
    void assignTaskToRobot_success(){
        Task createTask = createTask(ProductionLineType.LINE_A);
        Robot registerRobot = registerRobot();

        taskService.assignTaskToRobot(createTask.getId(), registerRobot.getId());

        entityManager.flush();
        entityManager.clear();

        Task foundTask = taskRepository.findById(createTask.getId())
            .orElseThrow();
        Robot foundRobot = robotRepository.findById(registerRobot.getId())
            .orElseThrow();

        assertEquals(registerRobot.getId(), foundTask.getRobot().getId());
        assertEquals(TaskStatus.ASSIGNED, foundTask.getStatus());

        assertEquals(createTask.getId(), foundRobot.getCurrentTask().getId());
        assertEquals(RobotOperationStatus.DELIVERING, foundRobot.getOperationStatus());
    }

    @Test
    @Transactional 
    void assignTaskToRobot_taskNotFound_throwsException(){
        Long nonexistentTaskId = 999999L;
        Robot robot = registerRobot();

        assertFalse(taskRepository.existsById(nonexistentTaskId));

        assertThrows(
            TaskNotFoundException.class,
            () -> taskService.assignTaskToRobot(nonexistentTaskId, robot.getId())
        );
    }

    @Test
    @Transactional 
    void assignTaskToRobot_robotNotFound_throwsException(){
        Task task = createTask(ProductionLineType.LINE_A);
        Long nonexistentRobotId = 999999L;

        assertFalse(robotRepository.existsById(nonexistentRobotId));

        assertThrows(
            RobotNotFoundException.class,
            () -> taskService.assignTaskToRobot(task.getId(), nonexistentRobotId)
        );
    }

    @Test 
    void assignTaskToRobot_taskAlreadyAssigned_throwsException(){
        Task task = createTask(ProductionLineType.LINE_A);
        Robot assignedRobot = registerRobot();
        Robot anotherRobot = registerRobot();

        taskService.assignTaskToRobot(task.getId(), assignedRobot.getId());

        assertThrows(
            IllegalStateException.class,
            () -> taskService.assignTaskToRobot(task.getId(), anotherRobot.getId())
        );

        Task foundTask = taskRepository.findById(task.getId())
            .orElseThrow();
        Robot foundRobot = robotRepository.findById(anotherRobot.getId())
            .orElseThrow();

        assertEquals(assignedRobot.getId(), foundTask.getRobot().getId());
        assertNull(foundRobot.getCurrentTask());
        assertEquals(RobotOperationStatus.IDLE, foundRobot.getOperationStatus());
    }

    @Test 
    void assignTaskToRobot_robotAlreadyDelivering_throwsException(){
        Task firstTask = createTask(ProductionLineType.LINE_A);
        Task secondTask = createTask(ProductionLineType.LINE_B);
        Robot robot = registerRobot();

        taskService.assignTaskToRobot(firstTask.getId(), robot.getId());

        assertThrows(
            IllegalStateException.class,
            () -> taskService.assignTaskToRobot(secondTask.getId(), robot.getId())
        );

        Task foundTask = taskRepository.findById(secondTask.getId())
            .orElseThrow();
        Robot foundRobot = robotRepository.findById(robot.getId())
            .orElseThrow();

        assertEquals(firstTask.getId(), foundRobot.getCurrentTask().getId());
        assertNull(foundTask.getRobot());
        assertEquals(TaskStatus.WAITING, foundTask.getStatus());
    }

    @Test 
    @Transactional
    void assignTaskToRobot_robotOffline_throwsException(){
        Task task = createTask(ProductionLineType.LINE_A);
        Robot robot = registerRobot();
        robot.disconnect();

        assertThrows(
            IllegalStateException.class,
            () -> taskService.assignTaskToRobot(task.getId(), robot.getId())
        );
        
        assertEquals(RobotConnectionStatus.OFFLINE, robot.getConnectionStatus());
        assertEquals(RobotOperationStatus.IDLE, robot.getOperationStatus());
        assertNull(robot.getCurrentTask());
    }
}