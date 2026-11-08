package com.fleetops.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RobotTest{

    @Test
    void robotRegister_success(){
        Robot robot = Robot.register();

        assertNull(robot.getId());
        assertEquals(RobotOperationStatus.IDLE, robot.getOperationStatus());
        assertEquals(RobotConnectionStatus.ONLINE, robot.getConnectionStatus());
        assertNull(robot.getCurrentTask());
    }

    @Test
    void robotStartDelivery_success(){
        Robot robot = Robot.register();
        ProductionLine line = new ProductionLine(ProductionLineType.LINE_A);
        Task task = new Task(line);

        robot.startDelivery(task);

        assertNull(robot.getId());
        assertEquals(RobotOperationStatus.DELIVERING, robot.getOperationStatus());
        assertEquals(RobotConnectionStatus.ONLINE, robot.getConnectionStatus());
        assertSame(task, robot.getCurrentTask());
    }

    @Test
    void robotStartDelivery_alreadyDelivering_throwsException(){
        Robot robot = Robot.register();

        ProductionLine lineA = new ProductionLine(ProductionLineType.LINE_A);
        Task taskA = new Task(lineA);
        robot.startDelivery(taskA);

        ProductionLine lineB = new ProductionLine(ProductionLineType.LINE_B);
        Task taskB = new Task(lineB);

        assertThrows(
            IllegalStateException.class,
            () -> robot.startDelivery(taskB)
        );

        assertEquals(RobotOperationStatus.DELIVERING, robot.getOperationStatus());
        assertEquals(RobotConnectionStatus.ONLINE, robot.getConnectionStatus());
        assertSame(taskA, robot.getCurrentTask());
    }

    @Test 
    void robotStartDelivery_nullTask_throwsException(){
        Robot robot = Robot.register();

        assertThrows(
            IllegalArgumentException.class,
            () -> robot.startDelivery(null)
        );

        assertEquals(RobotOperationStatus.IDLE, robot.getOperationStatus());
        assertEquals(RobotConnectionStatus.ONLINE, robot.getConnectionStatus());
        assertNull(robot.getCurrentTask());
    }

    @Test
    void robotStartDelivery_offline_throwsException(){
        Robot robot = Robot.register();
        ProductionLine line = new ProductionLine(ProductionLineType.LINE_A);
        Task task = new Task(line);

        robot.disconnect();

        assertThrows(
            IllegalStateException.class,
            () -> robot.startDelivery(task)
        );

        assertEquals(RobotOperationStatus.IDLE, robot.getOperationStatus());
        assertEquals(RobotConnectionStatus.OFFLINE, robot.getConnectionStatus());
        assertNull(robot.getCurrentTask());
    }

    @Test 
    void robotDisconnect_success(){
        Robot robot = Robot.register();
        ProductionLine line = new ProductionLine(ProductionLineType.LINE_A);
        Task task = new Task(line);

        robot.startDelivery(task);
        robot.disconnect();

        assertEquals(RobotOperationStatus.DELIVERING, robot.getOperationStatus());
        assertEquals(RobotConnectionStatus.OFFLINE, robot.getConnectionStatus());
        assertSame(task, robot.getCurrentTask());
    }

    @Test
    void robotDisconnect_twice_success(){
        Robot robot = Robot.register();
        ProductionLine line = new ProductionLine(ProductionLineType.LINE_A);
        Task task = new Task(line);

        robot.startDelivery(task);
        robot.disconnect();
        robot.disconnect(); // 멱등성 검증

        assertEquals(RobotOperationStatus.DELIVERING, robot.getOperationStatus());
        assertEquals(RobotConnectionStatus.OFFLINE, robot.getConnectionStatus());
        assertSame(task, robot.getCurrentTask());
    }
}