package com.fleetops.command;

public record TaskAssignedEvent(
    Long robotId, Long taskId, int travelSeconds
){}
