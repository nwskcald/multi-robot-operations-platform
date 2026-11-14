package com.fleetops.integration;

import com.fleetops.command.RobotCommandGateway;
import com.fleetops.domain.*;
import com.fleetops.mockamr.*;
import com.fleetops.repository.*;
import com.fleetops.service.*;
import org.junit.jupiter.api.*;
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
class HandoverIntegrationTest {
    @Autowired RobotRepository robots;
    @Autowired TaskRepository tasks;
    @Autowired ProductionLineRepository lines;
    @Autowired ProductionLineService service;
    @Autowired RobotService robotService;
    @Autowired PlatformTransactionManager manager;
    @Autowired TestRestTemplate http;
    @LocalServerPort int port;
    @MockitoBean RobotCommandGateway gateway;
    Long robotId, taskId;
    TransactionTemplate tx;

    @BeforeEach void prepare() {
        reset(gateway);
        tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(s -> {
            // Remove references before deleting the bidirectional association.
            robots.findAll().forEach(r -> org.springframework.test.util.ReflectionTestUtils.setField(r, "currentTask", null));
            robots.flush();
            tasks.deleteAll(); robots.deleteAll(); lines.deleteAll();
        });
        tx.executeWithoutResult(s -> {
            var line = lines.save(new ProductionLine(ProductionLineType.LINE_A));
            var robot = robots.save(Robot.register());
            var task = tasks.save(new Task(line));
            task.assignTo(robot); robot.startDelivery(task); robot.arriveAtDestination();
            robotId = robot.getId(); taskId = task.getId();
        });
    }
    void assertState(RobotOperationStatus robot, TaskStatus task, ProductionLineStatus line) {
        assertEquals(robot, robots.findById(robotId).orElseThrow().getOperationStatus());
        assertEquals(task, tasks.findById(taskId).orElseThrow().getStatus());
        assertEquals(line, lines.findById(ProductionLineType.LINE_A).orElseThrow().getStatus());
    }
    @Test void requestCommitsBeforeGateway() {
        doAnswer(call -> {
            assertState(RobotOperationStatus.HANDOVER, TaskStatus.ASSIGNED, ProductionLineStatus.OCCUPIED);
            return null;
        }).when(gateway).startHandover(robotId, 3);
        service.requestHandover(taskId, robotId);
        assertState(RobotOperationStatus.HANDOVER, TaskStatus.ASSIGNED, ProductionLineStatus.OCCUPIED);
        verify(gateway).startHandover(robotId, 3);
        assertEquals(robotId, lines.findById(ProductionLineType.LINE_A).orElseThrow().getOccupyingRobotId());
    }
    @Test void wrongRobotRejected() {
        var other = robotService.registerRobot();
        assertThrows(IllegalStateException.class, () -> service.requestHandover(taskId, other.getId()));
        verifyNoInteractions(gateway);
    }
    @Test void inconsistentTaskOwnerRejected() {
        tx.executeWithoutResult(s -> org.springframework.test.util.ReflectionTestUtils.setField(
            tasks.findById(taskId).orElseThrow(), "robot", robots.save(Robot.register())));
        assertThrows(IllegalStateException.class, () -> service.requestHandover(taskId, robotId));
        verifyNoInteractions(gateway);
    }
    @Test void wrongRobotStateRejected() {
        tx.executeWithoutResult(s -> org.springframework.test.util.ReflectionTestUtils.setField(
            robots.findById(robotId).orElseThrow(), "operationStatus", RobotOperationStatus.DELIVERING));
        assertThrows(IllegalStateException.class, () -> service.requestHandover(taskId, robotId));
        verifyNoInteractions(gateway);
    }
    @Test void occupiedLineRejected() {
        tx.executeWithoutResult(s -> lines.findById(ProductionLineType.LINE_A).orElseThrow().occupy(999L));
        assertThrows(IllegalStateException.class, () -> service.requestHandover(taskId, robotId));
        assertEquals(RobotOperationStatus.WAITING_FOR_HANDOVER, robots.findById(robotId).orElseThrow().getOperationStatus());
        verifyNoInteractions(gateway);
    }
    @Test void rollbackDoesNotDispatchOrPersist() {
        tx.executeWithoutResult(s -> {
            service.requestHandover(taskId, robotId);
            verifyNoInteractions(gateway);
            s.setRollbackOnly();
        });
        assertState(RobotOperationStatus.WAITING_FOR_HANDOVER, TaskStatus.ASSIGNED, ProductionLineStatus.AVAILABLE);
        verifyNoInteractions(gateway);
    }
    @Test void completeAndDuplicatePolicy() {
        service.requestHandover(taskId, robotId);
        robotService.completeHandover(robotId);
        assertState(RobotOperationStatus.RETURNING, TaskStatus.COMPLETED, ProductionLineStatus.AVAILABLE);
        assertNull(lines.findById(ProductionLineType.LINE_A).orElseThrow().getOccupyingRobotId());
        assertThrows(IllegalStateException.class, () -> robotService.completeHandover(robotId));
        assertState(RobotOperationStatus.RETURNING, TaskStatus.COMPLETED, ProductionLineStatus.AVAILABLE);
    }
    @Test void noCurrentTaskIsDomainError() {
        var idle = robotService.registerRobot();
        var ex = assertThrows(IllegalStateException.class, () -> robotService.completeHandover(idle.getId()));
        assertEquals("현재 수행 중인 Task가 없습니다.", ex.getMessage());
    }
    @Test void foreignOccupantRollsBackAllChanges() {
        service.requestHandover(taskId, robotId);
        tx.executeWithoutResult(s -> org.springframework.test.util.ReflectionTestUtils.setField(
            lines.findById(ProductionLineType.LINE_A).orElseThrow(), "occupyingRobotId", 999L));
        assertThrows(IllegalStateException.class, () -> robotService.completeHandover(robotId));
        assertState(RobotOperationStatus.HANDOVER, TaskStatus.ASSIGNED, ProductionLineStatus.OCCUPIED);
        assertEquals(999L, lines.findById(ProductionLineType.LINE_A).orElseThrow().getOccupyingRobotId());
    }
    @Test void duplicateStartRejected() {
        service.requestHandover(taskId, robotId);
        assertThrows(IllegalStateException.class, () -> service.requestHandover(taskId, robotId));
        verify(gateway, times(1)).startHandover(robotId, 3);
    }
    @Test void unassignedTaskStatusRejected() {
        tx.executeWithoutResult(s -> org.springframework.test.util.ReflectionTestUtils.setField(
            tasks.findById(taskId).orElseThrow(), "status", TaskStatus.COMPLETED));
        assertThrows(IllegalStateException.class, () -> service.requestHandover(taskId, robotId));
        verifyNoInteractions(gateway);
    }
    @Test void concurrentRequestsOccupyLineOnlyOnce() throws Exception {
        Long[] other = tx.execute(s -> {
            var robot = robots.save(Robot.register());
            var task = tasks.save(new Task(lines.findById(ProductionLineType.LINE_A).orElseThrow()));
            task.assignTo(robot); robot.startDelivery(task); robot.arriveAtDestination();
            return new Long[]{robot.getId(), task.getId()};
        });
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.function.BiFunction<Long, Long, Boolean> request = (r, t) -> {
                ready.countDown();
                try {
                    if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                    service.requestHandover(t, r);
                    return true;
                } catch (IllegalStateException rejected) { return false; }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            };
            var first = pool.submit(() -> request.apply(robotId, taskId));
            var second = pool.submit(() -> request.apply(other[0], other[1]));
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            boolean won = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(won, second.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(won ? robotId : other[0], lines.findById(ProductionLineType.LINE_A).orElseThrow().getOccupyingRobotId());
            assertEquals(RobotOperationStatus.WAITING_FOR_HANDOVER,
                robots.findById(won ? other[0] : robotId).orElseThrow().getOperationStatus());
            verify(gateway, times(1)).startHandover(anyLong(), eq(3));
        } finally { start.countDown(); pool.shutdownNow(); }
    }
    @Test void realHttpStartAndScheduledCompletion() throws Exception {
        String base = "http://localhost:" + port;
        var amr = new MockAmr(new ArrivalReporter(base), new HandoverCompletedReporter(base));
        try {
            doAnswer(call -> { new MockAmrGateway(amr).startHandover(call.getArgument(0), call.getArgument(1)); return null; })
                .when(gateway).startHandover(robotId, 3);
            var response = http.postForEntity("/api/robots/" + robotId + "/tasks/" + taskId + "/handover", null, Void.class);
            assertEquals(204, response.getStatusCode().value());
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(8)).untilAsserted(() ->
                assertState(RobotOperationStatus.RETURNING, TaskStatus.COMPLETED, ProductionLineStatus.AVAILABLE));
            // Reporter requires exactly HTTP 204; a duplicate must be rejected.
            assertThrows(IllegalStateException.class, () -> new HandoverCompletedReporter(base).reportHandoverCompleted(robotId));
        } finally { amr.shutdown(); }
    }
}
