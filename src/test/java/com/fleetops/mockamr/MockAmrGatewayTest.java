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
    void scheduledDeliveryReportsArrivalOverHttp() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        var received = new CountDownLatch(1);
        var method = new AtomicReference<String>();
        server.createContext("/api/robots/1/tasks/2/arrival", exchange -> {
            method.set(exchange.getRequestMethod());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            received.countDown();
        });
        server.start();
        var mockAmr = new MockAmr(new ArrivalReporter("http://localhost:" + server.getAddress().getPort()));
        try {
            new MockAmrGateway(mockAmr).deliver(1L, 2L, 1);
            assertFalse(received.await(100, TimeUnit.MILLISECONDS));
            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertEquals("POST", method.get());
        } finally {
            mockAmr.shutdown();
            server.stop(0);
        }
    }
}
