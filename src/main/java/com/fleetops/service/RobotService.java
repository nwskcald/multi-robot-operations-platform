package com.fleetops.service;

import com.fleetops.domain.*;
import com.fleetops.repository.*;
import com.fleetops.exception.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RobotService{

    private final RobotRepository robotRepository;
    private final TaskRepository taskRepository;
    private final ProductionLineRepository productionLineRepository;

    public RobotService(
        RobotRepository robotRepository,
        TaskRepository taskRepository,
        ProductionLineRepository productionLineRepository
    ){
        this.robotRepository = robotRepository;
        this.taskRepository = taskRepository;
        this.productionLineRepository = productionLineRepository;
    }

    @Transactional
    public Robot registerRobot(){
        Robot robot = Robot.register();
        return robotRepository.save(robot);
    }

    @Transactional
    public void reportArrival(Long taskId, Long robotId){
        Robot robot = robotRepository.findByIdForUpdate(robotId)
            .orElseThrow(
                () -> new RobotNotFoundException(robotId)
            );

        if(
            robot.getCurrentTask() == null ||
            !robot.getCurrentTask().getId().equals(taskId)
        ){
            throw new IllegalStateException("일치하지 않는 작업입니다.");
        }

        robot.arriveAtDestination();
    }

    @Transactional
    public void completeHandover(Long robotId){
        Robot robot = robotRepository.findByIdForUpdate(robotId)
            .orElseThrow(
                () -> new RobotNotFoundException(robotId)
            );
        Task currentTask = robot.getCurrentTask();
        if (currentTask == null) {
            throw new IllegalStateException(
                "현재 수행 중인 Task가 없습니다."
            );
        }

        Task task = taskRepository.findByIdForUpdate(currentTask.getId())
            .orElseThrow(
                () -> new TaskNotFoundException(currentTask.getId())
            );
        if (task.getRobot() == null || !robotId.equals(task.getRobot().getId())) {
            throw new IllegalStateException("Task가 해당 Robot에게 배정되지 않았습니다.");
        }
        ProductionLine productionLine =
            productionLineRepository.findByIdForUpdate(task.getProductionLine().getType())
                .orElseThrow(
                    () -> new ProductionLineNotFoundException(task.getProductionLine().getType())
                );

        robot.finishHandover();
        task.complete();
        productionLine.release(robotId);
    }
}
