package com.fleetops.domain;

public enum ProductionLineType{
    LINE_A(5, 3),
    LINE_B(10, 5),
    LINE_C(15, 7);

    private final int travelSeconds;
    private final int handoverSeconds;

    ProductionLineType(int travelSeconds, int handoverSeconds){
        this.travelSeconds = travelSeconds;
        this.handoverSeconds = handoverSeconds;
    }

    public int getTravelSeconds(){
        return travelSeconds;
    }

    public int getHandoverSeconds(){
        return handoverSeconds;
    }
}