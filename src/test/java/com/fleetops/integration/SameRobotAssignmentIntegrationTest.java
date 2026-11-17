package com.fleetops.integration;

import com.fleetops.command.RobotCommandGateway;
import com.fleetops.command.TaskAssignedEvent;
import com.fleetops.domain.*;
import com.fleetops.repository.*;
import com.fleetops.service.TaskService;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/cleanup.sql", config = @SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED))
class SameRobotAssignmentIntegrationTest {
    @Autowired TaskService taskService;
    @Autowired RobotRepository robots;
    @Autowired TaskRepository tasks;
    @Autowired ProductionLineRepository lines;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired AssignmentCommitGate gate;
    @MockitoBean RobotCommandGateway gateway;

    record Fixture(Long robotId, Long taskA, Long taskB) {}
    record Attempt(Long taskId, IllegalStateException rejection) {
        boolean succeeded() { return rejection == null; }
    }

    // No test-wide transaction: setup, each contender, and final reads commit independently.
    @Test
    void concurrentAssignments_sameRobot_onlyOneCommitsAndDispatches() throws Exception {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals("fleetops_test", jdbc.queryForObject("select current_database()", String.class));
        try (Connection connection = dataSource.getConnection()) {
            assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
        }
        // Two workers retain their connections; a third observes pg_stat_activity without blocking them.
        assertTrue(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize() >= 3);
        Fixture fixture = transaction().execute(status -> {
            ProductionLine line = lines.save(new ProductionLine(ProductionLineType.LINE_A));
            Robot robot = robots.save(Robot.register());
            Task a = tasks.save(new Task(line));
            Task b = tasks.save(new Task(line));
            assertEquals(RobotOperationStatus.IDLE, robot.getOperationStatus());
            assertNull(robot.getCurrentTask());
            assertEquals(TaskStatus.WAITING, a.getStatus());
            assertEquals(TaskStatus.WAITING, b.getStatus());
            assertNull(a.getRobot());
            assertNull(b.getRobot());
            return new Fixture(robot.getId(), a.getId(), b.getId());
        });
        assertNotNull(fixture);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Map<Long, Integer> backendPids = new ConcurrentHashMap<>();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> a = workers.submit(() -> assign(fixture.taskA(), fixture.robotId(), ready, start, backendPids));
            Future<Attempt> b = workers.submit(() -> assign(fixture.taskB(), fixture.robotId(), ready, start, backendPids));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both independent DB transactions must be ready");
            assertEquals(2, backendPids.values().stream().distinct().count(), "Workers must use different DB sessions");
            start.countDown();
            assertTrue(gate.assigned.await(10, TimeUnit.SECONDS), "One assignment must reach its pre-commit event");
            Long winnerId = gate.taskId;
            assertTrue(List.of(fixture.taskA(), fixture.taskB()).contains(winnerId));
            Long loserId = winnerId.equals(fixture.taskA()) ? fixture.taskB() : fixture.taskA();
            int winnerPid = backendPids.get(winnerId);
            int loserPid = backendPids.get(loserId);

            // The winner holds the real Robot row lock inside TaskService's transaction.
            // The loser is executing the other assignment, waiting on that DB session's Robot lock.
            // A start latch alone would not prove this overlap; PostgreSQL must report the blocker.
            await().pollInterval(Duration.ofMillis(25)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(Boolean.TRUE, jdbc.queryForObject("""
                    select exists (
                        select 1 from pg_stat_activity
                        where pid = ? and wait_event_type = 'Lock'
                          and ? = any(pg_blocking_pids(pid))
                          and query ilike '%robot%'
                    )
                    """, Boolean.class, loserPid, winnerPid)));
            assertFalse(a.isDone());
            assertFalse(b.isDone());
            verifyNoInteractions(gateway); // AFTER_COMMIT must not dispatch while winner is held.
            gate.allowCommit.countDown();

            // Future.get propagates unexpected worker errors. Only the domain rejection is captured.
            Attempt first = a.get(30, TimeUnit.SECONDS);
            Attempt second = b.get(30, TimeUnit.SECONDS);
            assertNotEquals(first.succeeded(), second.succeeded(), "Exactly one assignment must commit");
            Attempt winner = first.succeeded() ? first : second;
            Attempt loser = first.succeeded() ? second : first;
            assertEquals(winnerId, winner.taskId());
            assertEquals(loserId, loser.taskId());
            assertEquals(IllegalStateException.class, loser.rejection().getClass());
            assertEquals("IDLE 상태에서만 작업을 배정받을 수 있습니다.", loser.rejection().getMessage());
            assertEquals("com.fleetops.domain.Robot", loser.rejection().getStackTrace()[0].getClassName());
            assertEquals("startDelivery", loser.rejection().getStackTrace()[0].getMethodName());

            transaction().executeWithoutResult(status -> {
                Robot robot = robots.findById(fixture.robotId()).orElseThrow();
                Task assigned = tasks.findById(winnerId).orElseThrow();
                Task rejected = tasks.findById(loserId).orElseThrow();
                assertEquals(RobotOperationStatus.DELIVERING, robot.getOperationStatus());
                assertEquals(RobotConnectionStatus.ONLINE, robot.getConnectionStatus());
                assertEquals(winnerId, robot.getCurrentTask().getId());
                assertEquals(TaskStatus.ASSIGNED, assigned.getStatus());
                assertEquals(fixture.robotId(), assigned.getRobot().getId());
                // Task.assignTo ran before Robot.startDelivery rejected the loser: its changes must roll back.
                assertEquals(TaskStatus.WAITING, rejected.getStatus());
                assertNull(rejected.getRobot());
                List<Task> all = tasks.findAll();
                assertEquals(2, all.size());
                assertEquals(1, all.stream().filter(t -> t.getStatus() == TaskStatus.ASSIGNED).count());
                assertEquals(1, all.stream().filter(t -> t.getRobot() != null).count());
                assertEquals(1, robots.count());
                ProductionLine line = lines.findById(ProductionLineType.LINE_A).orElseThrow();
                assertEquals(ProductionLineStatus.AVAILABLE, line.getStatus());
                assertNull(line.getOccupyingRobotId());
            });
            verify(gateway, times(1)).deliver(fixture.robotId(), winnerId, ProductionLineType.LINE_A.getTravelSeconds());
            verifyNoMoreInteractions(gateway);
        } finally {
            start.countDown();
            gate.allowCommit.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "Workers must terminate before cleanup");
        }
    }

    private Attempt assign(Long taskId, Long robotId, CountDownLatch ready, CountDownLatch start,
                           Map<Long, Integer> backendPids) {
        try {
            transaction().executeWithoutResult(status -> {
                // Session-local test bounds; no application configuration is changed.
                entityManager.createNativeQuery("set local lock_timeout = '20s'").executeUpdate();
                entityManager.createNativeQuery("set local statement_timeout = '25s'").executeUpdate();
                int pid = ((Number) entityManager.createNativeQuery("select pg_backend_pid()").getSingleResult()).intValue();
                backendPids.put(taskId, pid);
                ready.countDown();
                awaitLatch(start, "Concurrent assignment start");
                taskService.assignTaskToRobot(taskId, robotId);
            });
            return new Attempt(taskId, null);
        } catch (IllegalStateException rejection) {
            return new Attempt(taskId, rejection);
        }
    }

    private TransactionTemplate transaction() {
        TransactionTemplate tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(40);
        return tx;
    }

    private static void awaitLatch(CountDownLatch latch, String description) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS), description + " timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(description + " interrupted", interrupted);
        }
    }

    @TestConfiguration
    static class Configuration {
        @Bean AssignmentCommitGate assignmentCommitGate() { return new AssignmentCommitGate(); }
    }

    static class AssignmentCommitGate {
        final CountDownLatch assigned = new CountDownLatch(1);
        final CountDownLatch allowCommit = new CountDownLatch(1);
        volatile Long taskId;

        // Synchronous test-only listener: hold the first successful assignment before commit.
        // Production AFTER_COMMIT listeners and repository queries execute unmodified.
        @EventListener
        public void onAssigned(TaskAssignedEvent event) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            taskId = event.taskId();
            assigned.countDown();
            awaitLatch(allowCommit, "Assignment commit release");
        }
    }
}
