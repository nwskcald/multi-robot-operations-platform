package com.fleetops.command;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TaskAssignedListener {
    private final RobotCommandGateway gateway;

    public TaskAssignedListener(RobotCommandGateway gateway) {
        this.gateway = gateway;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskAssigned(TaskAssignedEvent event) {
        gateway.deliver(event.robotId(), event.taskId(), event.travelSeconds());
    }
}
