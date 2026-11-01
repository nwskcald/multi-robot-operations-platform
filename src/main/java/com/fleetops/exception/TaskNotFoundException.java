package com.fleetops.exception;

public class TaskNotFoundException
    extends RuntimeException{

        public TaskNotFoundException(Long taskId){
            super("존재하지 않는 작업입니다: " + taskId);
        }
    }