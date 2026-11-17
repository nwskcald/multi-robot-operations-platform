package com.fleetops.command;

public record ProductionLineOccupiedEvent(
    Long robotId, Long taskId, int handoverSeconds
){}