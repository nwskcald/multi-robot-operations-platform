package com.fleetops.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskTest{

    @Test
    void taskCreate_success(){
        ProductionLine line = new ProductionLine(ProductionLineType.LINE_A);
        Task task = new Task(line);

        assertNull(task.getId());
        assertEquals(TaskStatus.WAITING, task.getStatus());

        assertNull(task.getRobot());
        assertSame(line, task.getProductionLine());
    }

    @Test
    void taskCreate_nullProductionLine_fail(){
        assertThrows(
            IllegalArgumentException.class,
            () -> new Task(null)
        );
    }

    @Test
    void taskAssign_success(){
        ProductionLine line = new ProductionLine(ProductionLineType.LINE_A);
        Task task = new Task(line);
        Robot robot = Robot.register();

        task.assignTo(robot);

        assertEquals(TaskStatus.ASSIGNED, task.getStatus());
        assertSame(robot, task.getRobot());
    }

    @Test
    void taskAssign_nullRobot_fail(){
        ProductionLine line = new ProductionLine(ProductionLineType.LINE_A);
        Task task = new Task(line);

        assertThrows(
            IllegalArgumentException.class,
            () -> task.assignTo(null)
        );

        assertEquals(TaskStatus.WAITING, task.getStatus());
        assertNull(task.getRobot());
    }


    @Test
    void taskAssign_differentRobot_fail(){
        ProductionLine line = new ProductionLine(ProductionLineType.LINE_A);
        Task task = new Task(line);
        Robot robot1 = Robot.register();
        task.assignTo(robot1);
        
        Robot robot2 = Robot.register();

        assertThrows(
            IllegalStateException.class,
            () -> task.assignTo(robot2)
        );

        assertEquals(TaskStatus.ASSIGNED, task.getStatus());
        assertSame(robot1, task.getRobot());
    }

    @Test 
    void taskAssign_sameRobot_fail(){
        ProductionLine line = new ProductionLine(ProductionLineType.LINE_A);
        Task task = new Task(line);
        Robot robot = Robot.register();

        task.assignTo(robot);
        
        assertThrows(
            IllegalStateException.class,
            () -> task.assignTo(robot)
        );

        assertEquals(TaskStatus.ASSIGNED, task.getStatus());
        assertSame(robot, task.getRobot());
    }
}