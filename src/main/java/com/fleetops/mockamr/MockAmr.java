package com.fleetops.mockamr;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MockAmr {

    private final ArrivalReporter arrivalReporter;
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2);

    public MockAmr(ArrivalReporter arrivalReporter) {
        this.arrivalReporter = arrivalReporter;
    }

    public void simulateDelivery(
            Long robotId,
            Long taskId,
            int travelSeconds
    ) {
        System.out.println(
            "Robot " + robotId + " 이동 시작!"
        );

        scheduler.schedule(
            () -> {
                try {
                    arrivalReporter.reportArrival(robotId, taskId);

                    System.out.println(
                        "Robot " + robotId + " 도착 보고 성공!"
                    );
                } catch (Exception e) {
                    System.err.println(
                        "Robot " + robotId
                        + " 도착 보고 실패: " + e.getMessage()
                    );
                }
            },
            travelSeconds,
            TimeUnit.SECONDS
        );
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}