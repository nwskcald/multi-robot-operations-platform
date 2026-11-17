package com.fleetops.command;

public interface RobotCommandGateway {
    void deliver(Long robotId, Long taskId, int travelSeconds);
    void startHandover(Long robotId, Long taskId, int handoverSeconds);
    void startReturn(Long robotId, Long taskId, int travelSeconds);
}
