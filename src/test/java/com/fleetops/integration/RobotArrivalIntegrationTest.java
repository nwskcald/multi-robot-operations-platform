package com.fleetops.integration;

import com.fleetops.repository.*;
import com.fleetops.service.*;
import com.fleetops.domain.*;
import com.fleetops.mockamr.*;

import java.time.Duration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import com.fleetops.controller.TaskController.CreateTaskRequest;
import com.fleetops.controller.TaskController.TaskCreatedResponse;

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
    private TestRestTemplate restTemplate;

    // 서버가 시작된 뒤 랜덤 포트를 읽어 실제 HTTP 콜백을 보낸다.
    @TestConfiguration
    static class CallbackConfiguration {
        @Bean
        @Primary
        HandoverCompletedReporter testHandoverReporter(Environment environment) {
            return new HandoverCompletedReporter("http://localhost") {
                @Override
                public void reportHandoverCompleted(Long robotId, Long taskId) throws Exception {
                    String baseUrl = "http://localhost:" + environment.getProperty("local.server.port");
                    HandoverCompletedReporter reporter = new HandoverCompletedReporter(baseUrl);
                    reporter.reportHandoverCompleted(robotId, taskId);
                }
            };
        }

        @Bean
        @Primary
        ProductionLineArrivalReporter testArrivalReporter(Environment environment) {
            return new ProductionLineArrivalReporter("http://localhost") {
                @Override
                public void reportArrival(Long robotId, Long taskId) throws Exception {
                    String baseUrl = "http://localhost:" + environment.getProperty("local.server.port");
                    ProductionLineArrivalReporter reporter = new ProductionLineArrivalReporter(baseUrl);
                    reporter.reportArrival(robotId, taskId);
                }
            };
        }
    }

    @Test 
    void assignTask_httpRequest_completesDeliveryAndHandover(){
        // Given
        ProductionLineType type = ProductionLineType.LINE_A;

        productionLineRepository.findById(type)
            .orElseGet(() ->
                productionLineRepository.save(
                    new ProductionLine(type)
                )
            );

        Robot robot = robotService.registerRobot();
        // When: API로 작업을 만들고 로봇에 배정한다.
        CreateTaskRequest createTaskRequest = new CreateTaskRequest(type);
        ResponseEntity<TaskCreatedResponse> createdResponse = restTemplate.postForEntity(
            "/api/tasks", createTaskRequest, TaskCreatedResponse.class);
        assertEquals(201, createdResponse.getStatusCode().value());
        assertNotNull(createdResponse.getBody());
        Task task = taskRepository.findById(createdResponse.getBody().taskId()).orElseThrow();
        ResponseEntity<Void> assignmentResponse = restTemplate.postForEntity("/api/tasks/" + task.getId()
            + "/robots/" + robot.getId() + "/assignment", null, Void.class);
        assertEquals(204, assignmentResponse.getStatusCode().value());

        // Then: 배정 직후에는 배송 중이고, Mock AMR 도착 콜백 후에는 인계 대기 상태이다.
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

        // When: 인계를 요청한다.
        ResponseEntity<Void> handoverResponse = restTemplate.postForEntity("/api/robots/" + robot.getId()
            + "/tasks/" + task.getId() + "/handover", null, Void.class);
        // Then: 라인을 점유한 뒤 완료 콜백으로 작업 완료 및 점유 해제가 반영된다.
        assertEquals(204, handoverResponse.getStatusCode().value());
        Robot handingOverRobot = robotRepository.findById(robot.getId()).orElseThrow();
        ProductionLine occupiedLine = productionLineRepository.findById(type).orElseThrow();
        assertEquals(RobotOperationStatus.HANDOVER, handingOverRobot.getOperationStatus());
        assertEquals(ProductionLineStatus.OCCUPIED, occupiedLine.getStatus());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Robot returningRobot = robotRepository.findById(robot.getId()).orElseThrow();
            Task completedTask = taskRepository.findById(task.getId()).orElseThrow();
            assertEquals(RobotOperationStatus.RETURNING, returningRobot.getOperationStatus());
            assertEquals(TaskStatus.COMPLETED, completedTask.getStatus());
            ProductionLine releasedLine = productionLineRepository.findById(type).orElseThrow();
            assertEquals(ProductionLineStatus.AVAILABLE, releasedLine.getStatus());
            assertNull(releasedLine.getOccupyingRobotId());
        });
    }
}