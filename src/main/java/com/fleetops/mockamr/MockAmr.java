package com.fleetops.mockamr;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MockAmr {

    private final ArrivalReporter arrivalReporter;
    private final HandoverCompletedReporter handoverCompletedReporter;
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(3);

    public MockAmr(
        ArrivalReporter arrivalReporter,
        HandoverCompletedReporter handoverCompletedReporter
    ){
        this.arrivalReporter = arrivalReporter;
        this.handoverCompletedReporter = handoverCompletedReporter;
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

     public void simulateHandover(
            Long robotId,
            int handoverSeconds
    ) {
        System.out.println(
            "Robot " + robotId + " 자재인계 시작!"
        );

        scheduler.schedule(
            () -> {
                try {
                    handoverCompletedReporter.reportHandoverCompleted(robotId);

                    System.out.println(
                        "Robot " + robotId + " 자재인계 완료 보고 성공!"
                    );
                } catch (Exception e) {
                    System.err.println(
                        "Robot " + robotId
                        + " 자재인계 완료 보고 실패: " + e.getMessage()
                    );
                }
            },
            handoverSeconds,
            TimeUnit.SECONDS
        );
    }


    public void shutdown() {
        scheduler.shutdownNow();
    }
}