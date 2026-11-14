package com.fleetops.command;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

import static org.mockito.Mockito.*;

class ProductionLineOccupiedListenerTest {
    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean RobotCommandGateway gateway() { return mock(RobotCommandGateway.class); }
        @Bean ProductionLineOccupiedListener listener(RobotCommandGateway gateway) {
            return new ProductionLineOccupiedListener(gateway);
        }
    }

    @Test
    void dispatchesOnlyAfterCommitAndSkipsRollbackAndNoTransaction() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var gateway = context.getBean(RobotCommandGateway.class);
            var manager = new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object tx, TransactionDefinition definition) {}
                @Override protected void doCommit(DefaultTransactionStatus status) {}
                @Override protected void doRollback(DefaultTransactionStatus status) {}
            };
            var tx = new TransactionTemplate(manager);
            var event = new ProductionLineOccupiedEvent(1L, 5);
            tx.executeWithoutResult(status -> {
                context.publishEvent(event);
                verifyNoInteractions(gateway);
            });
            verify(gateway).startHandover(1L, 5);
            clearInvocations(gateway);
            tx.executeWithoutResult(status -> {
                context.publishEvent(event);
                status.setRollbackOnly();
            });
            context.publishEvent(event);
            verifyNoInteractions(gateway);
        }
    }
}
