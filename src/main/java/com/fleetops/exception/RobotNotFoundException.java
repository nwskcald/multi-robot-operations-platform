package com.fleetops.exception;

public class RobotNotFoundException
    extends RuntimeException{

        public RobotNotFoundException(Long robotId){
            super("존재하지 않는 로봇입니다: " + robotId);
        }
    }