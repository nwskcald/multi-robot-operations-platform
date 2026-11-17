package com.fleetops.command;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component 
public class RobotFinishHandoverListener{
    private final RobotCommandGateway gateway;

    public RobotFinishHandoverListener(
        RobotCommandGateway gateway
    ){
        this.gateway = gateway;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRobotFinishHandover(RobotFinishHandoverEvent event){
        gateway.startReturn(event.robotId(), event.taskId(), event.travelSeconds());
    }
}