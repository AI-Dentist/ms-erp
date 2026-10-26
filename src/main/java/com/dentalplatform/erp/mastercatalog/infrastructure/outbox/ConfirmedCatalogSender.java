package com.dentalplatform.erp.mastercatalog.infrastructure.outbox;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** No database access on the sender thread. Even a stuck socket cannot hold the dispatcher's DB lock forever. */
@Component
public class ConfirmedCatalogSender {
    private static final Logger log = LoggerFactory.getLogger(ConfirmedCatalogSender.class);
    private final RabbitTemplate rabbit;
    private final CatalogOutboxSettings settings;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "catalog-outbox-send");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public ConfirmedCatalogSender(RabbitTemplate rabbit, CatalogOutboxSettings settings) {
        this.rabbit = rabbit;
        this.settings = settings;
    }

    public boolean send(UUID eventId, String exchange, String routingKey, String payload) {
        Future<Boolean> pending = null;
        try {
            pending = executor.submit(() -> {
                MessageProperties properties = new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setContentEncoding(StandardCharsets.UTF_8.name());
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                properties.setMessageId(eventId.toString());
                CorrelationData correlation = new CorrelationData(eventId.toString());
                rabbit.send(exchange, routingKey, new Message(payload.getBytes(StandardCharsets.UTF_8), properties), correlation);
                var confirm = correlation.getFuture().get(settings.sendTimeoutMs(), TimeUnit.MILLISECONDS);
                // Spring AMQP sets the returned message before completing the correlated confirm future.
                return confirm.ack() && correlation.getReturned() == null;
            });
            return pending.get(settings.sendTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            // Only the broker boundary is recoverable here; SQL failures are never caught by this adapter.
            log.warn("Catalog outbox send unconfirmed for event {} ({})", eventId, e.getClass().getSimpleName());
            return false;
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
