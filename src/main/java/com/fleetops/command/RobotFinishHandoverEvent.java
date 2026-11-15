package com.fleetops.command;

public record RobotFinishHandoverEvent(
    Long robotId, int travelSeconds
){}