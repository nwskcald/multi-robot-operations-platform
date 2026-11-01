package com.fleetops.domain;

import jakarta.persistence.*;

@Entity
public class ProductionLine{

    @Id
    @Enumerated(EnumType.STRING)
    private ProductionLineType type;

    @Enumerated(EnumType.STRING)
    private ProductionLineStatus status;

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
}