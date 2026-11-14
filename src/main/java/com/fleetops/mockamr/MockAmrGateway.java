package com.fleetops.mockamr;

import com.fleetops.command.RobotCommandGateway;
import org.springframework.stereotype.Component;

@Component
public class MockAmrGateway implements RobotCommandGateway {
    private final MockAmr mockAmr;

    public MockAmrGateway(MockAmr mockAmr) {
        this.mockAmr = mockAmr;
    }

    @Override
    public void deliver(Long robotId, Long taskId, int travelSeconds) {
        mockAmr.simulateDelivery(robotId, taskId, travelSeconds);
    }
}
