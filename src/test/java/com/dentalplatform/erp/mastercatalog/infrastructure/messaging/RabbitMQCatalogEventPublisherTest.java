package com.dentalplatform.erp.mastercatalog.infrastructure.messaging;

import com.dentalplatform.erp.mastercatalog.domain.event.ConsultorioCreadoEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RabbitMQCatalogEventPublisherTest {

    private static final String EXCHANGE = "dental.topic.exchange";
    private static final ConsultorioCreadoEvent EVENT =
            new ConsultorioCreadoEvent(UUID.randomUUID(), UUID.randomUUID(), "CONS-01", "Consultorio 1", "DISPONIBLE");

    private RabbitTemplate rabbitTemplate;
    private RabbitMQCatalogEventPublisher publisher;
    private TransactionTemplate transaction;

    /** Transaction manager with real synchronization lifecycle but no resource behind it. */
    private static final class StubTransactionManager extends AbstractPlatformTransactionManager {
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

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        publisher = new RabbitMQCatalogEventPublisher(rabbitTemplate, EXCHANGE);
        transaction = new TransactionTemplate(new StubTransactionManager());
    }

    @Test
    void sendsOnlyAfterTheTransactionCommits() {
        transaction.executeWithoutResult(status -> {
            publisher.publicar(EVENT);
            verifyNoInteractions(rabbitTemplate);
        });

        verify(rabbitTemplate, times(1)).convertAndSend(EXCHANGE, "catalogo.consultorio.creado", (Object) EVENT);
    }

    @Test
    void doesNotSendWhenTheTransactionRollsBack() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            publisher.publicar(EVENT);
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void doesNotSendWhenTheTransactionIsMarkedRollbackOnly() {
        transaction.executeWithoutResult(status -> {
            publisher.publicar(EVENT);
            status.setRollbackOnly();
        });

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void sendsImmediatelyWhenNoTransactionIsActive() {
        publisher.publicar(EVENT);

        verify(rabbitTemplate, times(1)).convertAndSend(EXCHANGE, "catalogo.consultorio.creado", (Object) EVENT);
    }

    @Test
    void brokerFailureAfterCommitDoesNotFailTheCommittedRequest() {
        doThrow(new AmqpConnectException(new IOException("down")))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        assertThatCode(() -> transaction.executeWithoutResult(status -> publisher.publicar(EVENT)))
                .doesNotThrowAnyException();
    }
}
