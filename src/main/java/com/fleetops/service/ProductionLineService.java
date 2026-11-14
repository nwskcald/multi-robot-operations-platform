package com.fleetops.service;

import com.fleetops.command.ProductionLineOccupiedEvent;
import com.fleetops.domain.*;
import com.fleetops.repository.*;
import com.fleetops.exception.*;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service 
public class ProductionLineService{

    private final TaskRepository taskRepository;
    private final RobotRepository robotRepository;
    private final ProductionLineRepository productionLineRepository;

    private final ApplicationEventPublisher events;

    public ProductionLineService(
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
    public void requestHandover(Long taskId, Long robotId){
        Robot robot = robotRepository.findByIdForUpdate(robotId)
            .orElseThrow(() -> new RobotNotFoundException(robotId));
        Task task = taskRepository.findByIdForUpdate(taskId)
            .orElseThrow(() -> new TaskNotFoundException(taskId));
        
        if(robot.getCurrentTask() == null || !robot.getCurrentTask().getId().equals(taskId)
            || task.getRobot() == null || !robotId.equals(task.getRobot().getId())){
            throw new IllegalStateException("Task가 해당 Robot에게 배정되지 않았습니다.");
        }

        ProductionLine productionLine = 
            productionLineRepository.findByIdForUpdate(task.getProductionLine().getType())
                .orElseThrow();

        productionLine.occupy(robotId);
        robot.startHandover();
        events.publishEvent(
            new ProductionLineOccupiedEvent(
                robotId,
                productionLine.getType().getHandoverSeconds()
            )
        );
    }
}