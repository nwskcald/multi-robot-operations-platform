package com.fleetops.integration;

import com.fleetops.command.RobotCommandGateway;
import com.fleetops.domain.*;
import com.fleetops.mockamr.*;
import com.fleetops.repository.*;
import com.fleetops.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.util.ReflectionTestUtils;
import static org.awaitility.Awaitility.await;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Sql(
    scripts = "/cleanup.sql",
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD,
    config = @SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED)
)
class HandoverIntegrationTest {

    @Autowired
    private RobotRepository robotRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ProductionLineRepository productionLineRepository;

    @Autowired
    private ProductionLineService productionLineService;

    @Autowired
    private RobotService robotService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int serverPort;

    @MockitoBean
    private RobotCommandGateway robotCommandGateway;

    private Long robotId;
    private Long taskId;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> {
            ProductionLine line = productionLineRepository.save(new ProductionLine(ProductionLineType.LINE_A));
            Robot robot = robotRepository.save(Robot.register());
            Task task = taskRepository.save(new Task(line));
            task.assignTo(robot);
            robot.startDelivery(task);
            robot.arriveAtDestination();
            robotId = robot.getId();
            taskId = task.getId();
        });
    }

    @Test
    void requestHandover_committedBeforeCommand_success() {
        // Given: setUp에서 인계 대기 중인 로봇과 작업을 준비한다.
        doAnswer(call -> {
            Robot foundRobot = robotRepository.findById(robotId).orElseThrow();
            Task foundTask = taskRepository.findById(taskId).orElseThrow();
            ProductionLine foundLine = productionLineRepository.findById(ProductionLineType.LINE_A).orElseThrow();

            assertEquals(RobotOperationStatus.HANDOVER, foundRobot.getOperationStatus());
            assertEquals(TaskStatus.ASSIGNED, foundTask.getStatus());
            assertEquals(ProductionLineStatus.OCCUPIED, foundLine.getStatus());
            return null;
        }).when(robotCommandGateway).startHandover(robotId, taskId, 3);

        // When
        productionLineService.requestHandover(taskId, robotId);

        // Then
        Robot foundRobot = robotRepository.findById(robotId).orElseThrow();
        Task foundTask = taskRepository.findById(taskId).orElseThrow();
        ProductionLine foundLine = productionLineRepository.findById(ProductionLineType.LINE_A).orElseThrow();

        assertEquals(RobotOperationStatus.HANDOVER, foundRobot.getOperationStatus());
        assertEquals(TaskStatus.ASSIGNED, foundTask.getStatus());
        assertEquals(ProductionLineStatus.OCCUPIED, foundLine.getStatus());
        verify(robotCommandGateway).startHandover(robotId, taskId, 3);
        assertEquals(robotId, foundLine.getOccupyingRobotId());
    }

    @Test
    void requestHandover_anotherRobot_throwsException() {
        // Given: setUp에서 인계 대기 중인 로봇과 작업을 준비한다.
        Robot anotherRobot = robotService.registerRobot();

        // When & Then
        assertThrows(
            IllegalStateException.class,
            () -> productionLineService.requestHandover(taskId, anotherRobot.getId())
        );
        verifyNoInteractions(robotCommandGateway);
    }

    @Test
    void requestHandover_inconsistentTaskOwner_throwsException() {
        // Given: setUp에서 인계 대기 중인 로봇과 작업을 준비한다.
        transactionTemplate.executeWithoutResult(status -> {
            Task task = taskRepository.findById(taskId).orElseThrow();
            // 정상 API로 만들 수 없는 불일치 상태를 검증하기 위한 설정이다.
            ReflectionTestUtils.setField(task, "robot", robotRepository.save(Robot.register()));
        });

        // When & Then
        assertThrows(
            IllegalStateException.class,
            () -> productionLineService.requestHandover(taskId, robotId)
        );
        verifyNoInteractions(robotCommandGateway);
    }

    @Test
    void requestHandover_deliveringRobot_throwsException() {
        // Given: setUp에서 인계 대기 중인 로봇과 작업을 준비한다.
        transactionTemplate.executeWithoutResult(status -> {
            Robot robot = robotRepository.findById(robotId).orElseThrow();
            // 정상 API로 만들 수 없는 불일치 상태를 검증하기 위한 설정이다.
            ReflectionTestUtils.setField(robot, "operationStatus", RobotOperationStatus.DELIVERING);
        });

        // When & Then
        assertThrows(
            IllegalStateException.class,
            () -> productionLineService.requestHandover(taskId, robotId)
        );
        verifyNoInteractions(robotCommandGateway);
    }

    @Test
    void requestHandover_occupiedLine_throwsException() {
        // Given: setUp에서 인계 대기 중인 로봇과 작업을 준비한다.
        transactionTemplate.executeWithoutResult(status -> {
            ProductionLine line = productionLineRepository.findById(ProductionLineType.LINE_A).orElseThrow();
            line.occupy(999L);
        });

        // When & Then
        assertThrows(
            IllegalStateException.class,
            () -> productionLineService.requestHandover(taskId, robotId)
        );
        Robot foundRobot = robotRepository.findById(robotId).orElseThrow();
        assertEquals(RobotOperationStatus.WAITING_FOR_HANDOVER, foundRobot.getOperationStatus());
        verifyNoInteractions(robotCommandGateway);
    }

    @Test
    void requestHandover_rollback_doesNotPersistOrSendCommand() {
        // Given: setUp에서 인계 대기 중인 로봇과 작업을 준비한다.
        // When
        transactionTemplate.executeWithoutResult(status -> {
            productionLineService.requestHandover(taskId, robotId);
            verifyNoInteractions(robotCommandGateway);
            status.setRollbackOnly();
        });

        // Then
        Robot foundRobot = robotRepository.findById(robotId).orElseThrow();
        Task foundTask = taskRepository.findById(taskId).orElseThrow();
        ProductionLine foundLine = productionLineRepository.findById(ProductionLineType.LINE_A).orElseThrow();

        assertEquals(RobotOperationStatus.WAITING_FOR_HANDOVER, foundRobot.getOperationStatus());
        assertEquals(TaskStatus.ASSIGNED, foundTask.getStatus());
        assertEquals(ProductionLineStatus.AVAILABLE, foundLine.getStatus());
        verifyNoInteractions(robotCommandGateway);
    }

    @Test
    void completeHandover_duplicateRequest_throwsExceptionAndPreservesState() {
        // Given: setUp에서 인계 대기 중인 로봇과 작업을 준비한다.
        productionLineService.requestHandover(taskId, robotId);

        // When: 첫 번째 완료 요청
        robotService.completeHandover(taskId, robotId);

        // Then
        Robot foundRobot = robotRepository.findById(robotId).orElseThrow();
        Task foundTask = taskRepository.findById(taskId).orElseThrow();
        ProductionLine foundLine = productionLineRepository.findById(ProductionLineType.LINE_A).orElseThrow();

        assertEquals(RobotOperationStatus.RETURNING, foundRobot.getOperationStatus());
        assertEquals(TaskStatus.COMPLETED, foundTask.getStatus());
        assertEquals(ProductionLineStatus.AVAILABLE, foundLine.getStatus());
        assertNull(foundLine.getOccupyingRobotId());

        // When & Then
        assertThrows(
            IllegalStateException.class,
            () -> robotService.completeHandover(taskId, robotId)
        );
        Robot foundRobotAfterDuplicate = robotRepository.findById(robotId).orElseThrow();
        Task foundTaskAfterDuplicate = taskRepository.findById(taskId).orElseThrow();
        ProductionLine foundLineAfterDuplicate = productionLineRepository.findById(ProductionLineType.LINE_A).orElseThrow();

        assertEquals(RobotOperationStatus.RETURNING, foundRobotAfterDuplicate.getOperationStatus());
        assertEquals(TaskStatus.COMPLETED, foundTaskAfterDuplicate.getStatus());
        assertEquals(ProductionLineStatus.AVAILABLE, foundLineAfterDuplicate.getStatus());
    }

    @Test
    void completeHandover_noCurrentTask_throwsException() {
        // Given: setUp에서 인계 대기 중인 로봇과 작업을 준비한다.
        Robot idleRobot = robotService.registerRobot();
        // When & Then
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> robotService.completeHandover(taskId, idleRobot.getId())
        );
        assertEquals("현재 수행 중인 Task가 없습니다.", exception.getMessage());
    }

    @Test
    void completeHandover_anotherOccupyingRobot_rollsBackAllChanges() {
        // Given: setUp에서 인계 대기 중인 로봇과 작업을 준비한다.
        productionLineService.requestHandover(taskId, robotId);
        transactionTemplate.executeWithoutResult(status -> {
            ProductionLine line = productionLineRepository.findById(ProductionLineType.LINE_A).orElseThrow();
            // 정상 API로 만들 수 없는 불일치 상태를 검증하기 위한 설정이다.
            ReflectionTestUtils.setField(line, "occupyingRobotId", 999L);
        });
        // When & Then: 완료 처리 도중 예외가 발생하면 앞서 변경한 상태도 롤백되어야 한다.
        assertThrows(
            IllegalStateException.class,
            () -> robotService.completeHandover(taskId, robotId)
        );

        Robot foundRobot = robotRepository.findById(robotId).orElseThrow();
        Task foundTask = taskRepository.findById(taskId).orElseThrow();
        ProductionLine foundLine = productionLineRepository.findById(ProductionLineType.LINE_A).orElseThrow();

        assertEquals(RobotOperationStatus.HANDOVER, foundRobot.getOperationStatus());
        assertEquals(TaskStatus.ASSIGNED, foundTask.getStatus());
        assertEquals(ProductionLineStatus.OCCUPIED, foundLine.getStatus());
        assertEquals(999L, foundLine.getOccupyingRobotId());
    }

    @Test
    void requestHandover_duplicateRequest_sendsCommandOnlyOnce() {
        // Given: setUp에서 인계 대기 중인 로봇과 작업을 준비한다.
        productionLineService.requestHandover(taskId, robotId);

        // When & Then
        assertThrows(
            IllegalStateException.class,
            () -> productionLineService.requestHandover(taskId, robotId)
        );
        verify(robotCommandGateway, times(1)).startHandover(robotId, taskId, 3);
    }

    @Test
    void requestHandover_completedTask_throwsException() {
        // Given: setUp에서 인계 대기 중인 로봇과 작업을 준비한다.
        transactionTemplate.executeWithoutResult(status -> {
            Task task = taskRepository.findById(taskId).orElseThrow();
            // 정상 API로 만들 수 없는 불일치 상태를 검증하기 위한 설정이다.
            ReflectionTestUtils.setField(task, "status", TaskStatus.COMPLETED);
        });

        // When & Then
        assertThrows(
            IllegalStateException.class,
            () -> productionLineService.requestHandover(taskId, robotId)
        );
        verifyNoInteractions(robotCommandGateway);
    }

    @Test
    void requestHandover_concurrentRequests_onlyOneSucceeds() throws Exception {
        // Given: 같은 라인을 향해 도착한 두 번째 로봇과 작업을 준비한다.
        Task anotherTask = transactionTemplate.execute(status -> {
            ProductionLine line = productionLineRepository.findById(ProductionLineType.LINE_A).orElseThrow();
            Robot anotherRobot = robotRepository.save(Robot.register());
            Task task = taskRepository.save(new Task(line));
            task.assignTo(anotherRobot);
            anotherRobot.startDelivery(task);
            anotherRobot.arriveAtDestination();
            return task;
        });
        Long anotherTaskId = anotherTask.getId();
        Long anotherRobotId = anotherTask.getRobot().getId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {

        // When: 두 스레드가 모두 준비된 후 동시에 요청한다.
            Future<Boolean> firstRequest = executor.submit(() ->
                requestHandoverAfterStart(taskId, robotId, ready, start));
            Future<Boolean> secondRequest = executor.submit(() ->
                requestHandoverAfterStart(anotherTaskId, anotherRobotId, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            boolean firstSucceeded = firstRequest.get(10, TimeUnit.SECONDS);
            boolean secondSucceeded = secondRequest.get(10, TimeUnit.SECONDS);


        // Then: 하나만 성공하고, 실패한 로봇은 인계 대기 상태를 유지한다.
            assertNotEquals(firstSucceeded, secondSucceeded);
            Long successfulRobotId;
            Long rejectedRobotId;
            if (firstSucceeded) {
                successfulRobotId = robotId;
                rejectedRobotId = anotherRobotId;
            } else {
                successfulRobotId = anotherRobotId;
                rejectedRobotId = robotId;
            }
            ProductionLine foundLine = productionLineRepository.findById(ProductionLineType.LINE_A).orElseThrow();
            Robot rejectedRobot = robotRepository.findById(rejectedRobotId).orElseThrow();
            assertEquals(successfulRobotId, foundLine.getOccupyingRobotId());
            assertEquals(ProductionLineStatus.OCCUPIED, foundLine.getStatus());
            assertEquals(RobotOperationStatus.WAITING_FOR_HANDOVER, rejectedRobot.getOperationStatus());
            verify(robotCommandGateway, times(1)).startHandover(anyLong(), anyLong(), eq(3));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void staleCompletionCallbacks_cannotChangeNextTaskOnSameRobot() {
        // Finish task A, then reuse its robot for task B on a different line.
        productionLineService.requestHandover(taskId, robotId);
        robotService.completeHandover(taskId, robotId);
        robotService.completeReturn(taskId, robotId);
        Long nextTaskId = transactionTemplate.execute(status -> {
            ProductionLine line = productionLineRepository.save(new ProductionLine(ProductionLineType.LINE_B));
            Robot robot = robotRepository.findByIdForUpdate(robotId).orElseThrow();
            Task task = taskRepository.save(new Task(line));
            task.assignTo(robot);
            robot.startDelivery(task);
            robot.arriveAtDestination();
            return task.getId();
        });
        productionLineService.requestHandover(nextTaskId, robotId);
        clearInvocations(robotCommandGateway);
        String prefix = "/api/robots/" + robotId + "/tasks/";

        // A's delayed completion must not complete B or release either line.
        assertEquals(500, restTemplate.postForEntity(prefix + taskId + "/handover-completion",
            null, String.class).getStatusCode().value());
        assertNextTaskState(nextTaskId, RobotOperationStatus.HANDOVER, TaskStatus.ASSIGNED,
            ProductionLineStatus.OCCUPIED, robotId);
        verifyNoInteractions(robotCommandGateway);

        // The matching callback still succeeds; duplicates of A remain invalid during B's return.
        assertEquals(204, restTemplate.postForEntity(prefix + nextTaskId + "/handover-completion",
            null, Void.class).getStatusCode().value());
        verify(robotCommandGateway).startReturn(robotId, nextTaskId, 10);
        clearInvocations(robotCommandGateway);
        assertEquals(500, restTemplate.postForEntity(prefix + taskId + "/arrival/base",
            null, String.class).getStatusCode().value());
        assertNextTaskState(nextTaskId, RobotOperationStatus.RETURNING, TaskStatus.COMPLETED,
            ProductionLineStatus.AVAILABLE, null);
        verifyNoInteractions(robotCommandGateway);

        assertEquals(204, restTemplate.postForEntity(prefix + nextTaskId + "/arrival/base",
            null, Void.class).getStatusCode().value());
        transactionTemplate.executeWithoutResult(status -> {
            Robot robot = robotRepository.findById(robotId).orElseThrow();
            assertEquals(RobotOperationStatus.IDLE, robot.getOperationStatus());
            assertNull(robot.getCurrentTask());
            assertEquals(robotId, taskRepository.findById(nextTaskId).orElseThrow().getRobot().getId());
        });
        // Unscoped legacy routes must never fall back to the current task.
        assertEquals(404, restTemplate.postForEntity("/api/robots/" + robotId + "/handover-completion",
            null, String.class).getStatusCode().value());
        assertEquals(404, restTemplate.postForEntity("/api/robots/" + robotId + "/arrival/base",
            null, String.class).getStatusCode().value());
    }

    private void assertNextTaskState(Long nextTaskId, RobotOperationStatus robotStatus,
                                    TaskStatus taskStatus, ProductionLineStatus lineStatus, Long occupant) {
        transactionTemplate.executeWithoutResult(status -> {
            Robot robot = robotRepository.findById(robotId).orElseThrow();
            Task next = taskRepository.findById(nextTaskId).orElseThrow();
            assertEquals(robotStatus, robot.getOperationStatus());
            assertEquals(nextTaskId, robot.getCurrentTask().getId());
            assertEquals(taskStatus, next.getStatus());
            assertEquals(robotId, next.getRobot().getId());
            assertEquals(lineStatus, next.getProductionLine().getStatus());
            assertEquals(occupant, next.getProductionLine().getOccupyingRobotId());
            Task previous = taskRepository.findById(taskId).orElseThrow();
            assertEquals(TaskStatus.COMPLETED, previous.getStatus());
            assertEquals(robotId, previous.getRobot().getId());
            assertEquals(ProductionLineStatus.AVAILABLE, previous.getProductionLine().getStatus());
            assertNull(previous.getProductionLine().getOccupyingRobotId());
        });
    }

    // 두 요청이 같은 출발 신호를 기다리게 한다. 예상한 도메인 거절만 false로 처리한다.
    private boolean requestHandoverAfterStart(Long requestedTaskId, Long requestedRobotId,
                                              CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("동시 요청 시작 신호를 받지 못했습니다.");
        }
        try {
            productionLineService.requestHandover(requestedTaskId, requestedRobotId);
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    @Test
    void requestHandover_httpRequest_completesThroughMockAmr() throws Exception {
        // Given: setUp에서 인계 대기 중인 로봇과 작업을 준비한다.
        String baseUrl = "http://localhost:" + serverPort;
        MockAmr mockAmr = new MockAmr(new ProductionLineArrivalReporter(baseUrl), new HandoverCompletedReporter(baseUrl), new BaseArrivalReporter(baseUrl));
        MockAmrGateway mockAmrGateway = new MockAmrGateway(mockAmr);
        try {
            // 이 테스트에서만 인계 명령을 실제 Mock AMR로 전달해 HTTP 완료 콜백까지 확인한다.
            doAnswer(call -> {
                mockAmrGateway.startHandover(robotId, taskId, 3);
                return null;
            })
                .when(robotCommandGateway).startHandover(robotId, taskId, 3);

            // When
            String handoverUrl = "/api/robots/" + robotId + "/tasks/" + taskId + "/handover";
            ResponseEntity<Void> response = restTemplate.postForEntity(handoverUrl, null, Void.class);

            // Then
            assertEquals(204, response.getStatusCode().value());
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
                Robot foundRobot = robotRepository.findById(robotId).orElseThrow();
                Task foundTask = taskRepository.findById(taskId).orElseThrow();
                ProductionLine foundLine = productionLineRepository.findById(ProductionLineType.LINE_A).orElseThrow();

                assertEquals(RobotOperationStatus.RETURNING, foundRobot.getOperationStatus());
                assertEquals(TaskStatus.COMPLETED, foundTask.getStatus());
                assertEquals(ProductionLineStatus.AVAILABLE, foundLine.getStatus());
            });
            // 완료 콜백은 HTTP 204만 성공으로 처리한다. 중복 완료는 거절되어야 한다.
            assertThrows(
                IllegalStateException.class,
                () -> new HandoverCompletedReporter(baseUrl).reportHandoverCompleted(robotId, taskId)
            );
        } finally {
            mockAmr.shutdown();
        }
    }
}
