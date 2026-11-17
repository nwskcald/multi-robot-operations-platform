package com.fleetops.command;

public record RobotFinishHandoverEvent(
    Long robotId, Long taskId, int travelSeconds
){}