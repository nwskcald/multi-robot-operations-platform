package com.fleetops.mockamr;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MockAmrGatewayTest {

    @Test
    void deliver_scheduledArrival_sendsHttpPost() throws Exception {
        // Given
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        CountDownLatch arrivalReceived = new CountDownLatch(1);
        AtomicReference<String> requestMethod = new AtomicReference<>();
        server.createContext("/api/robots/1/tasks/2/arrival", exchange -> {
            requestMethod.set(exchange.getRequestMethod());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            arrivalReceived.countDown();
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        ProductionLineArrivalReporter arrivalReporter = new ProductionLineArrivalReporter(baseUrl);
        HandoverCompletedReporter handoverReporter = new HandoverCompletedReporter(baseUrl);
        MockAmr mockAmr = new MockAmr(arrivalReporter, handoverReporter, new BaseArrivalReporter(baseUrl));
        try {
            // When
            new MockAmrGateway(mockAmr).deliver(1L, 2L, 1);

            // Then: 즉시 호출하지 않고 예약 시간이 지난 뒤 POST 요청을 보낸다.
            assertFalse(arrivalReceived.await(100, TimeUnit.MILLISECONDS));
            assertTrue(arrivalReceived.await(5, TimeUnit.SECONDS));
            assertEquals("POST", requestMethod.get());
        } finally {
            mockAmr.shutdown();
            server.stop(0);
        }
    }
}
