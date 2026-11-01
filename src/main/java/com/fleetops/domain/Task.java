package com.fleetops.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

@Entity 
public class Task{

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private TaskStatus status;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "robot_id", nullable = true)
    private Robot robot;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "production_line_type", nullable = false)
    private ProductionLine productionLine;

    protected Task(){
    }

    public Task(ProductionLine productionLine){
        if(productionLine == null){
            throw new IllegalArgumentException("작업 ProductionLine은 필수입니다.");
        }

        this.productionLine = productionLine;
        this.status = TaskStatus.WAITING;
    }

    public Long getId(){
        return id;
    }

    public TaskStatus getStatus(){
        return status;
    }

    public Robot getRobot(){
        return robot;
    }

    public ProductionLine getProductionLine(){
        return productionLine;
    }

    // Task 상태 변경 (WAITING -> ASSIGNED)
    public void assignTo(Robot robot){
        if(robot == null){
            throw new IllegalArgumentException("배정할 로봇은 필수입니다.");
        }

        if(this.robot != null){
            throw new IllegalStateException("이미 배정된 작업입니다.");
        }

        if(this.status != TaskStatus.WAITING){
            throw new IllegalStateException("WAITING 상태에서만 로봇을 배정할 수 있습니다.");
        }

        this.robot = robot;
        this.status = TaskStatus.ASSIGNED;
    }
}