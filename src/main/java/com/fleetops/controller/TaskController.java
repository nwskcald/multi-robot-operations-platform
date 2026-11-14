package com.fleetops.controller;

import com.fleetops.domain.ProductionLineType;
import com.fleetops.service.TaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    public record CreateTaskRequest(@NotNull ProductionLineType productionLineType) {}
    public record TaskCreatedResponse(Long taskId) {}

    @PostMapping
    public ResponseEntity<TaskCreatedResponse> create(@Valid @RequestBody CreateTaskRequest request) {
        var task = taskService.createTask(request.productionLineType());
        return ResponseEntity.status(201).body(new TaskCreatedResponse(task.getId()));
    }

    @PostMapping("/{taskId}/robots/{robotId}/assignment")
    public ResponseEntity<Void> assign(@PathVariable Long taskId, @PathVariable Long robotId) {
        taskService.assignTaskToRobot(taskId, robotId);
        return ResponseEntity.noContent().build();
    }
}
