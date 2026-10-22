package com.dentalplatform.erp.mastercatalog.infrastructure.messaging;

import com.dentalplatform.erp.mastercatalog.application.port.CatalogEventPublisherPort;
import com.dentalplatform.erp.mastercatalog.domain.event.ConsultorioCreadoEvent;
import com.dentalplatform.erp.mastercatalog.application.command.CrearConsultorioCommand;
import com.dentalplatform.erp.mastercatalog.application.usecase.CrearConsultorioUseCase;
import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

class RabbitMQCatalogEventPublisherTest extends IntegrationTestBase {
    @Autowired CatalogEventPublisherPort publisher;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CrearConsultorioUseCase createOffice;

    @BeforeEach
    void resetBroker() { clearInvocations(rabbitTemplate); }

    private ConsultorioCreadoEvent event() {
        return new ConsultorioCreadoEvent(UUID.randomUUID(), UUID.randomUUID(), "CONS-T", "Office", "DISPONIBLE");
    }

    private int count(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM catalog_event_outbox WHERE event_id = ?", Integer.class, id);
    }

    @Test
    void enqueueParticipatesInExistingTransactionWithoutNetwork() {
        var event = event();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            publisher.publicar(event);
            assertThat(count(event.eventId())).isOne();
            verifyNoInteractions(rabbitTemplate);
        });
        assertThat(count(event.eventId())).isOne();
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void exceptionRollsBackAggregateAuditAndOutbox() {
        String code = "T" + UUID.randomUUID().toString().substring(0, 8);
        int audits = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);
        int events = jdbc.queryForObject("SELECT count(*) FROM catalog_event_outbox", Integer.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            createOffice.ejecutar(new CrearConsultorioCommand(code, "Office", null));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consultorios WHERE codigo = ?", Integer.class, code)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class)).isEqualTo(audits);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_event_outbox", Integer.class)).isEqualTo(events);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void rollbackOnlyDoesNotLeaveAnEvent() {
        var event = event();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            publisher.publicar(event);
            status.setRollbackOnly();
        });
        assertThat(count(event.eventId())).isZero();
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void standalonePublicationStartsItsOwnTransaction() {
        var event = event();
        publisher.publicar(event);
        assertThat(count(event.eventId())).isOne();
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void duplicateEventIdSqlFailureCannotCommitPartiallyCreatedAggregate() {
        var event = event();
        publisher.publicar(event);
        String code = "T" + UUID.randomUUID().toString().substring(0, 8);
        int audits = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            createOffice.ejecutar(new CrearConsultorioCommand(code, "Office", null));
            publisher.publicar(event);
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consultorios WHERE codigo = ?", Integer.class, code)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class)).isEqualTo(audits);
        assertThat(count(event.eventId())).isOne();
        verifyNoInteractions(rabbitTemplate);
    }
}
