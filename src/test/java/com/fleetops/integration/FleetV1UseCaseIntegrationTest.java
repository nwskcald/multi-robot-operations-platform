package com.fleetops.integration;

import com.fleetops.command.*;
import com.fleetops.controller.RobotController.RobotCreatedResponse;
import com.fleetops.controller.TaskController.*;
import com.fleetops.domain.*;
import com.fleetops.mockamr.MockAmrGateway;
import com.fleetops.repository.*;
import com.fleetops.service.*;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL, real gateway/scheduler/reporters and real HTTP server. Spies only observe. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
@Sql(scripts = "/cleanup.sql", config = @SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED))
class FleetV1UseCaseIntegrationTest {
    // Supply the same ephemeral port to the server and unmodified production reporter configuration.
    private static final int PORT = availablePort();
    private static int availablePort() {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("mock-amr.base-url", () -> "http://localhost:" + PORT);
    }
    @Autowired TestRestTemplate http;
    @Autowired RobotRepository robots;
    @Autowired TaskRepository tasks;
    @Autowired ProductionLineRepository lines;
    @Autowired TaskService taskService;
    @Autowired RobotService robotService;
    @Autowired ProductionLineService lineService;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean MockAmrGateway gateway;
    TransactionTemplate tx;

    @BeforeEach void setup() { tx = new TransactionTemplate(manager); }

    record State(RobotOperationStatus robot, RobotConnectionStatus connection, Long currentTask,
                 TaskStatus task, Long owner, ProductionLineStatus line, Long occupyingRobot) {}
    State state(Long robotId, Long taskId) {
        return tx.execute(status -> {
            Robot robot = robots.findById(robotId).orElseThrow();
            Task task = tasks.findById(taskId).orElseThrow();
            ProductionLine line = task.getProductionLine();
            return new State(robot.getOperationStatus(), robot.getConnectionStatus(),
                robot.getCurrentTask() == null ? null : robot.getCurrentTask().getId(),
                task.getStatus(), task.getRobot() == null ? null : task.getRobot().getId(),
                line.getStatus(), line.getOccupyingRobotId());
        });
    }
    void post204(String path) { assertEquals(204, http.postForEntity(path, null, Void.class).getStatusCode().value()); }
    String assignment(Long task, Long robot) { return "/api/tasks/" + task + "/robots/" + robot + "/assignment"; }
    String robotPath(Long robot) { return "/api/robots/" + robot; }
    void awaitState(Long robot, Long task, State expected, int seconds) {
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(seconds + 8))
            .untilAsserted(() -> assertEquals(expected, state(robot, task)));
    }

    @ParameterizedTest
    @EnumSource(ProductionLineType.class)
    void realHttpCycle_andRobotReuse(ProductionLineType type) {
        lines.save(new ProductionLine(type));
        var response = http.postForEntity("/api/robots", null, RobotCreatedResponse.class);
        assertEquals(201, response.getStatusCode().value());
        assertNotNull(response.getBody());
        Long robot = response.getBody().robotId();
        // LINE_A repeats a full cycle to prove that currentTask cleanup permits reuse and preserves history.
        Long firstTask = cycle(robot, type);
        if (type == ProductionLineType.LINE_A) {
            Long secondTask = cycle(robot, type);
            assertNotEquals(firstTask, secondTask);
            assertEquals(TaskStatus.COMPLETED, tasks.findById(firstTask).orElseThrow().getStatus());
            assertEquals(robot, tasks.findById(firstTask).orElseThrow().getRobot().getId());
        }
    }
    Long cycle(Long robot, ProductionLineType type) {
        var created = http.postForEntity("/api/tasks", new CreateTaskRequest(type), TaskCreatedResponse.class);
        assertEquals(201, created.getStatusCode().value());
        assertNotNull(created.getBody());
        Long task = created.getBody().taskId();
        assertEquals(type, tasks.findById(task).orElseThrow().getProductionLine().getType());
        assertEquals(new State(RobotOperationStatus.IDLE, RobotConnectionStatus.ONLINE, null,
            TaskStatus.WAITING, null, ProductionLineStatus.AVAILABLE, null), state(robot, task));
        post204(assignment(task, robot));
        verify(gateway).deliver(robot, task, type.getTravelSeconds());
        assertEquals(new State(RobotOperationStatus.DELIVERING, RobotConnectionStatus.ONLINE, task,
            TaskStatus.ASSIGNED, robot, ProductionLineStatus.AVAILABLE, null), state(robot, task));
        awaitState(robot, task, new State(RobotOperationStatus.WAITING_FOR_HANDOVER, RobotConnectionStatus.ONLINE,
            task, TaskStatus.ASSIGNED, robot, ProductionLineStatus.AVAILABLE, null), type.getTravelSeconds());
        // Arrival alone must not complete the task. Handover requires the explicit implemented API.
        post204(robotPath(robot) + "/tasks/" + task + "/handover");
        assertEquals(new State(RobotOperationStatus.HANDOVER, RobotConnectionStatus.ONLINE, task,
            TaskStatus.ASSIGNED, robot, ProductionLineStatus.OCCUPIED, robot), state(robot, task));
        awaitState(robot, task, new State(RobotOperationStatus.RETURNING, RobotConnectionStatus.ONLINE, task,
            TaskStatus.COMPLETED, robot, ProductionLineStatus.AVAILABLE, null), type.getHandoverSeconds());
        State returning = state(robot, task);
        assertEquals(500, http.postForEntity(robotPath(robot) + "/tasks/" + task + "/handover-completion", null, String.class).getStatusCode().value());
        assertEquals(returning, state(robot, task));
        Long waiting = taskService.createTask(type).getId();
        State before = state(robot, waiting);
        assertThrows(IllegalStateException.class, () -> taskService.assignTaskToRobot(waiting, robot));
        assertEquals(before, state(robot, waiting));
        awaitState(robot, task, new State(RobotOperationStatus.IDLE, RobotConnectionStatus.ONLINE, null,
            TaskStatus.COMPLETED, robot, ProductionLineStatus.AVAILABLE, null), type.getTravelSeconds());
        State idle = state(robot, task);
        assertEquals(500, http.postForEntity(robotPath(robot) + "/tasks/" + task + "/arrival/base", null, String.class).getStatusCode().value());
        assertEquals(idle, state(robot, task));
        // The only producer of these calls in this test is the real AFTER_COMMIT event chain.
        // A reused robot legitimately receives a second handover/return command.
        verify(gateway, atLeastOnce()).startHandover(robot, task, type.getHandoverSeconds());
        verify(gateway, atLeastOnce()).startReturn(robot, task, type.getTravelSeconds());
        return task;
    }

    Long[] fixture(RobotOperationStatus desired, boolean wrongOccupant) {
        return tx.execute(status -> {
            ProductionLine line = lines.save(new ProductionLine(ProductionLineType.LINE_A));
            Robot robot = robots.save(Robot.register());
            Task task = tasks.save(new Task(line));
            if (desired != RobotOperationStatus.IDLE) { task.assignTo(robot); robot.startDelivery(task); }
            if (desired == RobotOperationStatus.WAITING_FOR_HANDOVER || desired == RobotOperationStatus.HANDOVER) robot.arriveAtDestination();
            if (desired == RobotOperationStatus.HANDOVER) { line.occupy(wrongOccupant ? 999999L : robot.getId()); robot.startHandover(); }
            return new Long[]{robot.getId(), task.getId()};
        });
    }

    @Test void rollbackAssignment_neverSendsRealCommand() {
        Long[] ids = fixture(RobotOperationStatus.IDLE, false);
        State before = state(ids[0], ids[1]);
        tx.executeWithoutResult(status -> {
            taskService.assignTaskToRobot(ids[1], ids[0]);
            verifyNoInteractions(gateway);
            status.setRollbackOnly();
        });
        assertEquals(before, state(ids[0], ids[1]));
        verifyNoInteractions(gateway);
    }
    @Test void offlineAssignment_rollsBackTaskMutation() {
        Long[] ids = fixture(RobotOperationStatus.IDLE, false);
        tx.executeWithoutResult(status -> robots.findById(ids[0]).orElseThrow().disconnect());
        State before = state(ids[0], ids[1]);
        assertThrows(IllegalStateException.class, () -> taskService.assignTaskToRobot(ids[1], ids[0]));
        assertEquals(before, state(ids[0], ids[1]));
        verifyNoInteractions(gateway);
    }
    @Test void invalidHandover_rollsBackLineOccupation() {
        Long[] ids = fixture(RobotOperationStatus.DELIVERING, false);
        State before = state(ids[0], ids[1]);
        assertThrows(IllegalStateException.class, () -> lineService.requestHandover(ids[1], ids[0]));
        assertEquals(before, state(ids[0], ids[1]));
        verifyNoInteractions(gateway);
    }
    @Test void failedRelease_rollsBackRobotAndTaskAndSendsNoReturn() {
        Long[] ids = fixture(RobotOperationStatus.HANDOVER, true);
        State before = state(ids[0], ids[1]);
        assertThrows(IllegalStateException.class, () -> robotService.completeHandover(ids[1], ids[0]));
        assertEquals(before, state(ids[0], ids[1]));
        verifyNoInteractions(gateway);
    }
    @Test void rolledBackCompletion_preservesAllThreeEntitiesAndSendsNoReturn() {
        Long[] ids = fixture(RobotOperationStatus.HANDOVER, false);
        State before = state(ids[0], ids[1]);
        tx.executeWithoutResult(status -> {
            robotService.completeHandover(ids[1], ids[0]);
            verifyNoInteractions(gateway);
            status.setRollbackOnly();
        });
        assertEquals(before, state(ids[0], ids[1]));
        verifyNoInteractions(gateway);
    }
    @Test void invalidAndDuplicateCallbacks_preserveDatabase() {
        Long[] ids = fixture(RobotOperationStatus.DELIVERING, false);
        String path = robotPath(ids[0]);
        State before = state(ids[0], ids[1]);
        // Current implementation has no exception mapping: domain rejection is HTTP 500.
        for (String suffix : List.of("/tasks/" + ids[1] + "/handover-completion", "/tasks/" + ids[1] + "/arrival/base",
                "/tasks/999999/arrival/productionline")) {
            assertEquals(500, http.postForEntity(path + suffix, null, String.class).getStatusCode().value());
            assertEquals(before, state(ids[0], ids[1]));
        }
        post204(path + "/tasks/" + ids[1] + "/arrival/productionline");
        State waiting = state(ids[0], ids[1]);
        assertEquals(RobotOperationStatus.WAITING_FOR_HANDOVER, waiting.robot());
        assertEquals(500, http.postForEntity(path + "/tasks/" + ids[1] + "/arrival/productionline", null, String.class).getStatusCode().value());
        assertEquals(waiting, state(ids[0], ids[1]));
        verifyNoInteractions(gateway);
    }
    @Test void invalidCreateRequests_doNotCreateTasks() {
        for (String body : List.of("{}", "{\"productionLineType\":\"UNKNOWN\"}")) {
            var headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            var request = new org.springframework.http.HttpEntity<>(body, headers);
            assertEquals(400, http.postForEntity("/api/tasks", request, String.class).getStatusCode().value());
        }
        assertEquals(0, tasks.count());
        assertEquals(500, http.postForEntity("/api/tasks", new CreateTaskRequest(ProductionLineType.LINE_A), String.class).getStatusCode().value());
        assertEquals(0, tasks.count());
    }
    @Test void eventsWithoutTransaction_doNotSendCommands() {
        events.publishEvent(new TaskAssignedEvent(1L, 2L, 5));
        events.publishEvent(new ProductionLineOccupiedEvent(1L, 2L, 3));
        events.publishEvent(new RobotFinishHandoverEvent(1L, 2L, 5));
        verifyNoInteractions(gateway);
    }
}
