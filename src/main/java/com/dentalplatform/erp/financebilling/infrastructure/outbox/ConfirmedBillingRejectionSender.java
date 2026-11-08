package com.dentalplatform.erp.financebilling.infrastructure.outbox;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Only AMQP runs on this bounded worker; its caller retains ownership of the database transaction. */
@Component
public class ConfirmedBillingRejectionSender {
    private static final Logger log = LoggerFactory.getLogger(ConfirmedBillingRejectionSender.class);
    private final RabbitTemplate rabbit;
    private final BillingRejectionOutboxSettings settings;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "billing-rejection-outbox-send");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public ConfirmedBillingRejectionSender(RabbitTemplate rabbit, BillingRejectionOutboxSettings settings) {
        this.rabbit = rabbit;
        this.settings = settings;
    }

    public boolean send(UUID eventId, String exchange, String routingKey, String payload) {
        Future<Boolean> pending = null;
        try {
            pending = executor.submit(() -> {
                var properties = new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setContentEncoding(StandardCharsets.UTF_8.name());
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                properties.setMessageId(eventId.toString());
                var correlation = new CorrelationData(eventId.toString());
                rabbit.send(exchange, routingKey,
                        new Message(payload.getBytes(StandardCharsets.UTF_8), properties), correlation);
                var confirm = correlation.getFuture().get(settings.sendTimeoutMs(), TimeUnit.MILLISECONDS);
                // Correlated returns are populated before Spring completes the confirm future.
                return confirm.ack() && correlation.getReturned() == null;
            });
            // Covers queueing, send/connect stalls AND waiting for the correlated confirm.
            return pending.get(settings.sendTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException | RejectedExecutionException unavailable) {
            log.warn("Billing rejection send unconfirmed ({})", unavailable.getClass().getSimpleName());
            return false;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            // The executor and confirm future can each wrap the same broker failure.
            for (int depth = 0; depth < 32 && cause != null && cause.getCause() != null
                    && (cause instanceof ExecutionException || cause instanceof CompletionException); depth++) {
                cause = cause.getCause();
            }
            if (cause instanceof AmqpException || cause instanceof TimeoutException) {
                log.warn("Billing rejection send unconfirmed ({})", cause.getClass().getSimpleName());
                return false;
            }
            // Programming failures are not broker failures, and must roll back the caller's transaction.
            throw new IllegalStateException("Billing rejection sender failed", failed);
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            executor.purge();
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
