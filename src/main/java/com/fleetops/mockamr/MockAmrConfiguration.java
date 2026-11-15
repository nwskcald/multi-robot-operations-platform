package com.fleetops.mockamr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MockAmrConfiguration {
    @Bean
    ProductionLineArrivalReporter productionLineArrivalReporter(
            @Value("${mock-amr.base-url:http://localhost:${server.port:8080}}") String baseUrl) {
        return new ProductionLineArrivalReporter(baseUrl);
    }

    @Bean
    HandoverCompletedReporter handoverCompletedReporter(
            @Value("${mock-amr.base-url:http://localhost:${server.port:8080}}") String baseUrl) {
        return new HandoverCompletedReporter(baseUrl);
    }

    @Bean
    BaseArrivalReporter baseArrivalReporter(
            @Value("${mock-amr.base-url:http://localhost:${server.port:8080}}") String baseUrl) {
        return new BaseArrivalReporter(baseUrl);
    }

    @Bean(destroyMethod = "shutdown")
    MockAmr mockAmr(
        ProductionLineArrivalReporter productionLineArrivalReporter,
        HandoverCompletedReporter handoverCompletedReporter,
        BaseArrivalReporter baseArrivalReporter
    ){
        return new MockAmr(
            productionLineArrivalReporter, 
            handoverCompletedReporter,
            baseArrivalReporter
        );
    }
}
