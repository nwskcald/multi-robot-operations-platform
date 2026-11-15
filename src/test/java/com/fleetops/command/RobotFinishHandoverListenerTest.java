package com.fleetops.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.Mockito.*;

class RobotFinishHandoverListenerTest {

    private AnnotationConfigApplicationContext context;
    private RobotCommandGateway gateway;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        gateway = context.getBean(RobotCommandGateway.class);
        transactionTemplate = new TransactionTemplate(new TestTransactionManager());
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void startReturn_afterCommit_success() {
        // Given
        RobotFinishHandoverEvent event = new RobotFinishHandoverEvent(1L, 5);

        // When
        transactionTemplate.executeWithoutResult(status -> {
            context.publishEvent(event);
            // 아직 커밋하지 않았으므로 명령을 보내면 안 된다.
            verifyNoInteractions(gateway);
        });

        // Then
        verify(gateway).startReturn(1L, 5);
    }

    @Test
    void startReturn_rollback_doesNotSendCommand() {
        // Given
        RobotFinishHandoverEvent event = new RobotFinishHandoverEvent(1L, 5);

        // When
        transactionTemplate.executeWithoutResult(status -> {
            context.publishEvent(event);
            status.setRollbackOnly();
        });

        // Then
        verifyNoInteractions(gateway);
    }

    @Test
    void startReturn_withoutTransaction_doesNotSendCommand() {
        // Given
        RobotFinishHandoverEvent event = new RobotFinishHandoverEvent(1L, 5);

        // When
        context.publishEvent(event);

        // Then
        verifyNoInteractions(gateway);
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfiguration {

        @Bean
        RobotCommandGateway gateway() {
            return mock(RobotCommandGateway.class);
        }

        @Bean
        RobotFinishHandoverListener listener(RobotCommandGateway gateway) {
            return new RobotFinishHandoverListener(gateway);
        }
    }

    // DB 저장은 하지 않고 Spring의 커밋/롤백 이벤트 실행 시점만 확인한다.
    // 실제 DB 롤백은 HandoverIntegrationTest에서 별도로 확인한다.
    static class TestTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
