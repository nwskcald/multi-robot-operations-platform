package com.fleetops.service;

import com.fleetops.domain.*;
import com.fleetops.repository.*;
import com.fleetops.exception.*;
import com.fleetops.command.RobotFinishHandoverEvent;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

@Service
public class RobotService{

    private final RobotRepository robotRepository;
    private final TaskRepository taskRepository;
    private final ProductionLineRepository productionLineRepository;

    private final ApplicationEventPublisher events;

    public RobotService(
        RobotRepository robotRepository,
        TaskRepository taskRepository,
        ProductionLineRepository productionLineRepository,
        ApplicationEventPublisher events
    ){
        this.robotRepository = robotRepository;
        this.taskRepository = taskRepository;
        this.productionLineRepository = productionLineRepository;
        this.events = events;
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
        if (robot.getCurrentTask() == null) {
            throw new IllegalStateException("현재 수행 중인 Task가 없습니다.");
        }

        Task task = taskRepository.findByIdForUpdate(robot.getCurrentTask().getId())
            .orElseThrow(
                () -> new TaskNotFoundException(robot.getCurrentTask().getId())
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
        events.publishEvent(
            new RobotFinishHandoverEvent(
                robotId,
                productionLine.getType().getTravelSeconds()
            )
        );
    }

    @Transactional 
    public void completeReturn(Long robotId){
        Robot robot = robotRepository.findByIdForUpdate(robotId)
            .orElseThrow(
                () -> new RobotNotFoundException(robotId)
            );
        if(robot.getCurrentTask() == null){
            throw new IllegalStateException("현재 수행 중인 Task가 없습니다.");
        }

        Task task = taskRepository.findByIdForUpdate(robot.getCurrentTask().getId())
            .orElseThrow(
                () -> new TaskNotFoundException(robot.getCurrentTask().getId())
            );
        
        if(task.getRobot() == null || !task.getRobot().getId().equals(robotId)){
            throw new IllegalStateException("Task가 해당 Robot에게 배정되지 않았습니다.");
        }

        if(!task.isTaskStatusCompleted()){
            throw new IllegalStateException("아직 완료되지 않은 Task 입니다.");
        }

        robot.finishReturn();
    }
}
