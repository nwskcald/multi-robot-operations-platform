package com.fleetops.service;

import com.fleetops.domain.*;
import com.fleetops.repository.*;
import com.fleetops.exception.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RobotService{

    private final RobotRepository robotRepository;

    public RobotService(
        RobotRepository robotRepository
    ){
        this.robotRepository = robotRepository;
    }

    @Transactional
    public Robot registerRobot(){
        Robot robot = Robot.register();
        return robotRepository.save(robot);
    }
}