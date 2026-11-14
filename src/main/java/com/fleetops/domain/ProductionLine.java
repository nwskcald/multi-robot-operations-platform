package com.fleetops.domain;

import jakarta.persistence.*;

@Entity
public class ProductionLine{

    @Id
    @Enumerated(EnumType.STRING)
    private ProductionLineType type;

    @Enumerated(EnumType.STRING)
    private ProductionLineStatus status;

    private Long occupyingRobotId;

    protected ProductionLine(){
    }

    public ProductionLine(ProductionLineType type){
        if(type == null){
            throw new IllegalStateException("생산라인 Type은 필수입니다.");
        }

        this.type = type;
        this.status = ProductionLineStatus.AVAILABLE;
    }

    public ProductionLineType getType(){
        return type;
    }

    public ProductionLineStatus getStatus(){
        return status;
    }

    public Long getOccupyingRobotId() {
        return occupyingRobotId;
    }

    // ProductionLine 상태 변경 (AVAILABLE -> OCCUPIED)
    public void occupy(Long occupyingRobotId){
        if(occupyingRobotId == null){
            throw new IllegalArgumentException("점유할 로봇은 필수입니다.");
        }
        if(this.status != ProductionLineStatus.AVAILABLE){
            throw new IllegalStateException("점유할 수 없는 ProductionLine 입니다.");
        }

        this.occupyingRobotId = occupyingRobotId;
        this.status = ProductionLineStatus.OCCUPIED;
    }

    // ProductionLine 상태 변경 (OCCUPIED -> AVAILABLE)
    public void release(Long robotId){
        if(this.status != ProductionLineStatus.OCCUPIED){
            throw new IllegalStateException("점유를 해제할 수 없는 ProductionLine 입니다.");
        }
        if(robotId == null || !robotId.equals(this.occupyingRobotId)){
            throw new IllegalStateException("점유하고 있는 로봇과 일치하지 않는 Robot 입니다.");
        }

        this.occupyingRobotId = null;
        this.status = ProductionLineStatus.AVAILABLE;
    }
}