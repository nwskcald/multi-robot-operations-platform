package com.fleetops.controller;

import com.fleetops.service.RobotService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/robots")
public class RobotController{

    private final RobotService robotService;

    public RobotController(RobotService robotService){
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
} 