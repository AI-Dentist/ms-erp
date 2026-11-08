package com.dentalplatform.erp.financebilling.infrastructure.messaging;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.function.LongConsumer;

@Component
public class ConsultaFinalizadaRetryPolicy {
    private final int maxAttempts;
    private final long initialDelay;
    private final long maxDelay;
    private final long redeliveryDelay;
    private final LongConsumer pause;

    public ConsultaFinalizadaRetryPolicy(
            @Value("${erp.billing-saga.retry.max-attempts:3}") int maxAttempts,
            @Value("${erp.billing-saga.retry.initial-delay-ms:100}") long initialDelay,
            @Value("${erp.billing-saga.retry.max-delay-ms:2000}") long maxDelay,
            @Value("${erp.billing-saga.retry.redelivery-delay-ms:1000}") long redeliveryDelay,
            @Qualifier("billingSagaRetryPause") LongConsumer pause) {
        if (maxAttempts < 2 || maxAttempts > 10 || initialDelay < 1 || maxDelay < initialDelay
                || redeliveryDelay < 100 || redeliveryDelay > 30000) {
            throw new IllegalArgumentException("Billing saga retry requires attempts 2..10, positive delays, "
                    + "max-delay-ms >= initial-delay-ms and redelivery-delay-ms 100..30000");
        }
        this.maxAttempts = maxAttempts;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.redeliveryDelay = redeliveryDelay;
        this.pause = pause;
    }

    public void ejecutar(Runnable attempt) {
        long delay = initialDelay;
        for (int remaining = maxAttempts; remaining > 0; remaining--) {
            checkInterrupted();
            try {
                attempt.run();
                return;
            } catch (RuntimeException failure) {
                checkInterrupted();
                if (remaining == 1 || !isTransient(failure)) {
                    throw failure;
                }
            }
            pause.accept(delay);
            delay = delay >= maxDelay - delay ? maxDelay : delay * 2;
        }
    }

    boolean agotados(Throwable failure, int attempts) {
        return !Thread.currentThread().isInterrupted() && attempts == maxAttempts && isTransient(failure);
    }

    long redeliveryDelayMs() { return redeliveryDelay; }

    void pauseBeforeRedelivery() {
        checkInterrupted();
        pause.accept(redeliveryDelay);
        checkInterrupted();
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new ImmediateRequeueAmqpException("Billing saga retry interrupted");
        }
    }

    private static boolean isTransient(Throwable failure) {
        boolean transientCause = false;
        // Bounded traversal also terminates cyclic cause chains; an incomplete chain is not retried.
        int depth = 0;
        Throwable cause = failure;
        for (; cause != null && depth < 32; cause = cause.getCause(), depth++) {
            if (cause instanceof BusinessRuleException || cause instanceof IllegalArgumentException) {
                return false;
            }
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                boolean retryableSql = sql instanceof SQLTransientException || sql instanceof SQLRecoverableException
                        || (state != null && (state.startsWith("08") || state.equals("40001")
                        || state.equals("40P01") || state.equals("55P03") || state.equals("57P03")));
                if (!retryableSql) {
                    return false;
                }
                transientCause = true;
            }
            transientCause |= cause instanceof TransientDataAccessException
                    || cause instanceof RecoverableDataAccessException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof SocketException || cause instanceof SocketTimeoutException
                    || cause instanceof AmqpConnectException || cause instanceof AmqpIOException;
        }
        // CannotCreateTransactionException alone is deliberately insufficient: require a resource/connect cause.
        return cause == null && transientCause;
    }
}
