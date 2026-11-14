package com.fleetops.mockamr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MockAmrConfiguration {
    @Bean
    ArrivalReporter arrivalReporter(
            @Value("${mock-amr.base-url:http://localhost:${server.port:8080}}") String baseUrl) {
        return new ArrivalReporter(baseUrl);
    }

    @Bean(destroyMethod = "shutdown")
    MockAmr mockAmr(ArrivalReporter arrivalReporter) {
        return new MockAmr(arrivalReporter);
    }
}
