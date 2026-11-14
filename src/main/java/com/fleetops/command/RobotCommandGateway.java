package com.fleetops.command;

public interface RobotCommandGateway {
    void deliver(Long robotId, Long taskId, int travelSeconds);
    void startHandover(Long robotId, int handoverSeconds);
}
