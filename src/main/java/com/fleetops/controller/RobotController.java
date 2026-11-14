package com.fleetops.controller;

import com.fleetops.service.RobotService;

import com.fleetops.service.ProductionLineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/robots")
public class RobotController{

    private final RobotService robotService;

    private final ProductionLineService productionLineService;

    public RobotController(RobotService robotService, ProductionLineService productionLineService){
        this.productionLineService = productionLineService;
        this.robotService = robotService;
    }

    public record RobotCreatedResponse(Long robotId) {}

    @PostMapping
    public ResponseEntity<RobotCreatedResponse> register() {
        return ResponseEntity.status(201).body(
            new RobotCreatedResponse(robotService.registerRobot().getId()));
    }

    @PostMapping("/{robotId}/tasks/{taskId}/arrival")
    public ResponseEntity<Void> reportArrival(
        @PathVariable Long robotId,
        @PathVariable Long taskId
    ){
        robotService.reportArrival(taskId, robotId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{robotId}/tasks/{taskId}/handover")
    public ResponseEntity<Void> requestHandover(@PathVariable Long robotId, @PathVariable Long taskId) {
        productionLineService.requestHandover(taskId, robotId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{robotId}/handover-completion")
    public ResponseEntity<Void> completeHandover(
        @PathVariable Long robotId
    ){
        robotService.completeHandover(robotId);
        return ResponseEntity.noContent().build();
    }
} 