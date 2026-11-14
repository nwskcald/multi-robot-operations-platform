package com.fleetops.command;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ProductionLineOccupiedListener{
    private final RobotCommandGateway gateway;

    public ProductionLineOccupiedListener(
        RobotCommandGateway gateway
    ){
        this.gateway = gateway;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProductionLineOccupied(ProductionLineOccupiedEvent event){
        gateway.startHandover(event.robotId(), event.handoverSeconds());
    }
}