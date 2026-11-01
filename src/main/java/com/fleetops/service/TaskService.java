package com.fleetops.service;

import com.fleetops.domain.*;
import com.fleetops.repository.*;
import com.fleetops.exception.*;
import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;

@Service
public class TaskService{

    private final TaskRepository taskRepository;
    private final RobotRepository robotRepository;
    private final ProductionLineRepository productionLineRepository;

    public TaskService(
        TaskRepository taskRepository,
        RobotRepository robotRepository,
        ProductionLineRepository productionLineRepository
    ){
        this.taskRepository = taskRepository;
        this.robotRepository = robotRepository;
        this.productionLineRepository = productionLineRepository;
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
}
