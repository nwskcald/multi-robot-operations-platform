package com.fleetops.service;

import com.fleetops.domain.*;
import com.fleetops.repository.*;
import com.fleetops.exception.*;

import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import com.fleetops.command.TaskAssignedEvent;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService{

    private final TaskRepository taskRepository;
    private final RobotRepository robotRepository;
    private final ProductionLineRepository productionLineRepository;

    private final ApplicationEventPublisher events;

    public TaskService(
        TaskRepository taskRepository,
        RobotRepository robotRepository,
        ProductionLineRepository productionLineRepository,
        ApplicationEventPublisher events
    ){
        this.taskRepository = taskRepository;
        this.robotRepository = robotRepository;
        this.productionLineRepository = productionLineRepository;
        this.events = events;
    }

    @Transactional
    public Task createTask(ProductionLineType type){
        ProductionLine line = productionLineRepository.findById(type)
            .orElseThrow(
                () -> new ProductionLineNotFoundException(type)
            );
        
        Task task = new Task(line);
        return taskRepository.save(task);
    }

    @Transactional 
    public void assignTaskToRobot(Long taskId, Long robotId){
        Task task = taskRepository.findByIdForUpdate(taskId)
            .orElseThrow(
                () -> new TaskNotFoundException(taskId)
            );
        
        Robot robot = robotRepository.findByIdForUpdate(robotId)
            .orElseThrow(
                () -> new RobotNotFoundException(robotId)
            );
        
        task.assignTo(robot);
        robot.startDelivery(task);
        events.publishEvent(
            new TaskAssignedEvent(
                robotId, 
                taskId,
                task.getProductionLine().getType().getTravelSeconds()
            )
        );
    }

}
