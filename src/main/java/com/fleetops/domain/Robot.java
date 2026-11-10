package com.fleetops.domain;

import jakarta.persistence.*;

@Entity
public class Robot{

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private RobotOperationStatus operationStatus;

    @Enumerated(EnumType.STRING)
    private RobotConnectionStatus connectionStatus;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_task_id")
    private Task currentTask;

    protected Robot(){
    }

    public static Robot register(){
        Robot robot = new Robot();
        robot.operationStatus = RobotOperationStatus.IDLE;
        robot.connectionStatus = RobotConnectionStatus.ONLINE;
        return robot;
    }

    public Long getId(){
        return id;
    }

    public RobotOperationStatus getOperationStatus(){
        return operationStatus;
    }

    public RobotConnectionStatus getConnectionStatus(){
        return connectionStatus;
    }

    public Task getCurrentTask(){
        return currentTask;
    }

    public void disconnect(){
        this.connectionStatus = RobotConnectionStatus.OFFLINE;
    }

    // Robot 상태 변경 (IDLE -> DELIVERING)
    public void startDelivery(Task task){
        if(task == null){
            throw new IllegalArgumentException("배정받을 작업은 필수입니다.");
        }

        if(this.connectionStatus != RobotConnectionStatus.ONLINE){
            throw new IllegalStateException("ONLINE 상태에서만 작업을 배정받을 수 있습니다.");
        }

        if(this.operationStatus != RobotOperationStatus.IDLE){
            throw new IllegalStateException("IDLE 상태에서만 작업을 배정받을 수 있습니다.");
        }

        if(this.currentTask != null){
            throw new IllegalStateException("작업중인 로봇입니다.");
        }

        this.currentTask = task;
        this.operationStatus = RobotOperationStatus.DELIVERING;
    }

    public void arriveAtDestination(){
        if(this.operationStatus != RobotOperationStatus.DELIVERING){
            throw new IllegalStateException("작업중이지 않은 로봇입니다.");
        }

        if(this.connectionStatus != RobotConnectionStatus.ONLINE){
            throw new IllegalStateException("ONLINE 상태가 아닌 로봇입니다.");
        }

        if(this.currentTask == null){
            throw new IllegalStateException("작업중이지 않은 로봇입니다.");
        }

        this.operationStatus = RobotOperationStatus.WAITING_FOR_HANDOVER;
    }
}