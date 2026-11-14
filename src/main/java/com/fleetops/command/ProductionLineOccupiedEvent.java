package com.fleetops.command;

public record ProductionLineOccupiedEvent(
    Long robotId, int handoverSeconds
){}