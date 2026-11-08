package com.dentalplatform.erp.mastercatalog.infrastructure.messaging;

import com.dentalplatform.erp.mastercatalog.application.port.CatalogEventPublisherPort;
import com.dentalplatform.erp.mastercatalog.domain.event.ConsultorioCreadoEvent;
import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Autowired;
import com.dentalplatform.erp.mastercatalog.infrastructure.outbox.CatalogOutboxDispatcher;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;

@TestPropertySource(properties = "erp.catalog-outbox.send-timeout-ms=1000")
class CatalogOutboxDispatcherIntegrationTest extends IntegrationTestBase {
    @Autowired CatalogEventPublisherPort publisher;
    @Autowired CatalogOutboxDispatcher dispatcher;
    @Autowired com.dentalplatform.erp.mastercatalog.infrastructure.outbox.ConfirmedCatalogSender sender;
    @Autowired com.dentalplatform.erp.mastercatalog.infrastructure.outbox.CatalogOutboxSettings settings;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase auditoria;

    @BeforeEach
    void isolateDueRows() {
        clearInvocations(rabbitTemplate);
        jdbc.update("UPDATE catalog_event_outbox SET next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 day' WHERE sent_at IS NULL");
    }

    private UUID enqueue() {
        UUID id = UUID.randomUUID();
        publisher.publicar(new ConsultorioCreadoEvent(id, UUID.randomUUID(), "CONS-T", "Office", "DISPONIBLE"));
        return id;
    }

    private void dispatch() {
        dispatcher.dispatch();
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM catalog_event_outbox WHERE event_id = ?", id);
    }

    private void ack() {
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"outage", "nack", "return", "timeout"})
    void failuresRemainDurableAndRetryWithIdenticalIdAndPayload(String failure) {
        UUID id = enqueue();
        List<Message> messages = new ArrayList<>();
        List<String> correlations = new ArrayList<>();
        List<CorrelationData> confirmations = new ArrayList<>();
        doAnswer(call -> {
            Message message = call.getArgument(2);
            messages.add(message);
            assertThat(call.<String>getArgument(0)).isEqualTo("dental.topic.exchange");
            assertThat(call.<String>getArgument(1)).isEqualTo("catalogo.consultorio.creado");
            CorrelationData correlation = call.getArgument(3);
            correlations.add(correlation.getId());
            confirmations.add(correlation);
            assertThat(row(id).get("sent_at")).isNull();
            if (failure.equals("outage")) throw new AmqpException("broker down");
            if (failure.equals("return")) correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", "dental.topic.exchange", "catalogo.consultorio.creado"));
            if (!failure.equals("timeout")) correlation.getFuture().complete(new CorrelationData.Confirm(!failure.equals("nack"), null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        dispatch();
        Map<String, Object> failed = row(id);
        assertThat(failed.get("sent_at")).isNull();
        assertThat(failed.get("attempts")).isEqualTo(1);
        assertThat(failed.get("next_attempt_at").toString()).isNotEqualTo(failed.get("created_at").toString());
        if (failure.equals("timeout")) {
            confirmations.getFirst().getFuture().complete(new CorrelationData.Confirm(true, null));
            assertThat(row(id)).isEqualTo(failed); // late ACK cannot mutate SQL state after the timeout
        }
        clearInvocations(rabbitTemplate);
        dispatch(); // not due: no spinning on an outage
        verifyNoInteractions(rabbitTemplate);
        doAnswer(call -> {
            messages.add(call.getArgument(2));
            CorrelationData correlation = call.getArgument(3);
            correlations.add(correlation.getId());
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        jdbc.update("UPDATE catalog_event_outbox SET next_attempt_at = CURRENT_TIMESTAMP WHERE event_id = ?", id);
        dispatch();
        assertThat(row(id).get("sent_at")).isNotNull();
        assertThat(row(id).get("attempts")).isEqualTo(2);
        assertThat(correlations).containsExactly(id.toString(), id.toString());
        assertThat(messages.get(1).getBody()).isEqualTo(messages.get(0).getBody());
        assertThat(messages.get(1).getMessageProperties().getMessageId()).isEqualTo(id.toString());
        assertThat(messages.get(1).getMessageProperties().getDeliveryMode()).isEqualTo(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
        clearInvocations(rabbitTemplate);
        dispatch();
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void concurrentWorkersSkipLockedRowsAndDoNotMarkUntilAck() throws Exception {
        UUID first = enqueue();
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            sending.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            call.<CorrelationData>getArgument(3).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(this::dispatch);
            assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(row(first).get("sent_at")).isNull();
            var otherWorker = new CatalogOutboxDispatcher(jdbc, sender, settings, transactions, auditoria);
            var two = executor.submit(otherWorker::dispatch);
            two.get(500, TimeUnit.MILLISECONDS); // must skip the locked row, not wait for its sender
            release.countDown();
            one.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertThat(row(first).get("sent_at")).isNotNull();
        assertThat(row(first).get("attempts")).isEqualTo(1);
    }

    @Test
    void acceptanceBeforeLostDatabaseCommitRedeliversSameEvent() {
        UUID id = enqueue();
        List<String> ids = new ArrayList<>();
        List<byte[]> payloads = new ArrayList<>();
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            ids.add(correlation.getId());
            payloads.add(call.<Message>getArgument(2).getBody());
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        // Model the crash window: broker accepted, but the DB transaction is lost instead of committed.
        var lostCommit = new org.springframework.transaction.PlatformTransactionManager() {
            public org.springframework.transaction.TransactionStatus getTransaction(
                    org.springframework.transaction.TransactionDefinition definition) {
                return transactions.getTransaction(definition);
            }
            public void commit(org.springframework.transaction.TransactionStatus status) {
                transactions.rollback(status);
                throw new IllegalStateException("simulated lost database commit after broker ACK");
            }
            public void rollback(org.springframework.transaction.TransactionStatus status) {
                transactions.rollback(status);
            }
        };
        var crashingWorker = new CatalogOutboxDispatcher(jdbc, sender, settings, lostCommit, auditoria);
        org.assertj.core.api.Assertions.assertThatThrownBy(crashingWorker::dispatch)
                .isInstanceOf(IllegalStateException.class);
        assertThat(row(id).get("sent_at")).isNull();
        assertThat(row(id).get("attempts")).isEqualTo(0);
        dispatch();
        assertThat(row(id).get("sent_at")).isNotNull();
        assertThat(ids).containsExactly(id.toString(), id.toString());
        assertThat(payloads.get(1)).isEqualTo(payloads.getFirst());
    }

    @Test
    void independentWorkersCanDeliverDifferentRowsWhileOneIsLocked() throws Exception {
        UUID first = enqueue();
        UUID second = enqueue();
        CountDownLatch sendingFirst = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            if (correlation.getId().equals(first.toString())) {
                sendingFirst.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            }
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        var otherSender = new com.dentalplatform.erp.mastercatalog.infrastructure.outbox.ConfirmedCatalogSender(rabbitTemplate, settings);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(this::dispatch);
            assertThat(sendingFirst.await(5, TimeUnit.SECONDS)).isTrue();
            var otherWorker = new CatalogOutboxDispatcher(jdbc, otherSender, settings, transactions, auditoria);
            executor.submit(otherWorker::dispatch).get(500, TimeUnit.MILLISECONDS);
            assertThat(row(second).get("sent_at")).isNotNull();
            assertThat(row(first).get("sent_at")).isNull();
            release.countDown();
            one.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            otherSender.close();
        }
        assertThat(row(first).get("attempts")).isEqualTo(1);
        assertThat(row(second).get("attempts")).isEqualTo(1);
    }

    @Test
    void blockedBrokerCallCannotHoldDatabaseLockPastSendDeadline() throws Exception {
        UUID id = enqueue();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        doAnswer(call -> {
            try {
                // Model a socket/library call that ignores cancellation. The DB thread still has a deadline.
                while (release.getCount() > 0) {
                    try { release.await(); } catch (InterruptedException ignored) { }
                }
                call.<CorrelationData>getArgument(3).getFuture().complete(new CorrelationData.Confirm(true, null));
                return null;
            } finally {
                finished.countDown();
            }
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        try {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), this::dispatch);
            Map<String, Object> timedOut = row(id);
            assertThat(timedOut.get("sent_at")).isNull();
            assertThat(timedOut.get("attempts")).isEqualTo(1);
            release.countDown();
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(row(id)).isEqualTo(timedOut);
        } finally {
            release.countDown();
        }
    }

    @Test
    void defaultBatchIsOneAndDeliveredRowsAreRetained() {
        UUID first = enqueue();
        UUID second = enqueue();
        ack();
        dispatch();
        assertThat(List.of(row(first), row(second)).stream().filter(r -> r.get("sent_at") != null).count()).isOne();
        dispatch();
        assertThat(row(first).get("sent_at")).isNotNull();
        assertThat(row(second).get("sent_at")).isNotNull();
    }
}
