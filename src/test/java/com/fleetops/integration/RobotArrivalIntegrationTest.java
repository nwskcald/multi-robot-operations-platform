package com.fleetops.integration;

import com.fleetops.repository.*;
import com.fleetops.service.*;
import com.fleetops.domain.*;
import com.fleetops.mockamr.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
public class RobotArrivalIntegrationTest{

    @Autowired
    private RobotService robotService;

    @Autowired
    private RobotRepository robotRepository;

    @Autowired 
    private TaskRepository taskRepository;

    @Autowired
    private ProductionLineRepository productionLineRepository;

    @Autowired
    private org.springframework.boot.test.web.client.TestRestTemplate http;

    @org.springframework.boot.test.context.TestConfiguration
    static class CallbackConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        ArrivalReporter testArrivalReporter(org.springframework.core.env.Environment environment) {
            return new ArrivalReporter("http://localhost") {
                @Override
                public void reportArrival(Long robotId, Long taskId) throws Exception {
                    new ArrivalReporter("http://localhost:" + environment.getProperty("local.server.port"))
                        .reportArrival(robotId, taskId);
                }
            };
        }
    }

    @Test 
    void apiAssignmentAutomaticallySimulatesDelivery(){
        ProductionLineType type = ProductionLineType.LINE_A;

        productionLineRepository.findById(type)
            .orElseGet(() ->
                productionLineRepository.save(
                    new ProductionLine(type)
                )
            );


        Robot robot = robotService.registerRobot();
        var created = http.postForEntity("/api/tasks",
            java.util.Map.of("productionLineType", "LINE_A"),
            com.fleetops.controller.TaskController.TaskCreatedResponse.class);
        assertEquals(201, created.getStatusCode().value());
        Task task = taskRepository.findById(created.getBody().taskId()).orElseThrow();
        var assigned = http.postForEntity("/api/tasks/" + task.getId()
            + "/robots/" + robot.getId() + "/assignment", null, Void.class);
        assertEquals(204, assigned.getStatusCode().value());

        Robot assignedRobot = robotRepository.findById(robot.getId())
            .orElseThrow();

        assertEquals(
            RobotOperationStatus.DELIVERING,
            assignedRobot.getOperationStatus()
        );

        await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(
                () -> {
                    Robot foundRobot = robotRepository.findById(robot.getId())
                        .orElseThrow();
                    Task foundTask = taskRepository.findById(task.getId())
                        .orElseThrow();
                    
                    assertEquals(
                        RobotOperationStatus.WAITING_FOR_HANDOVER,
                        foundRobot.getOperationStatus()
                    );
                    assertEquals(
                        TaskStatus.ASSIGNED,
                        foundTask.getStatus()
                    );
                }
            );
    }
}