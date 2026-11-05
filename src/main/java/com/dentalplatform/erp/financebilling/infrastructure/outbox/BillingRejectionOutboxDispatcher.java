package com.dentalplatform.erp.financebilling.infrastructure.outbox;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/** Committed rows only; a lost DB commit after ACK may resend the same stable eventId (at least once). */
@Component
public class BillingRejectionOutboxDispatcher implements Runnable {
    private static final String UNCONFIRMED = "PUBLICACION_NO_CONFIRMADA";
    private static final String ENCODING_FAILED = "CODIFICACION_FALLIDA";
    private final JdbcTemplate jdbc;
    private final ConfirmedBillingRejectionSender sender;
    private final BillingRejectionOutboxSettings settings;
    private final RegistrarAuditoriaUseCase auditoria;
    private final JsonMapper json;
    private final Clock clock;
    private final String exchange;
    private final String routingKey;
    private final TransactionTemplate transaction;

    public BillingRejectionOutboxDispatcher(JdbcTemplate jdbc, ConfirmedBillingRejectionSender sender,
            BillingRejectionOutboxSettings settings, RegistrarAuditoriaUseCase auditoria, JsonMapper json,
            Clock clock, PlatformTransactionManager manager,
            @Value("${erp.messaging.exchange:dental.topic.exchange}") String exchange,
            @Value("${erp.messaging.facturacion-rechazada-routing-key:facturacion.consulta.rechazada}") String routingKey) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.settings = settings;
        this.auditoria = auditoria;
        this.json = json;
        this.clock = clock;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void run() { dispatch(); }

    public void dispatch() {
        for (int i = 0; i < settings.batchSize(); i++) {
            if (!Boolean.TRUE.equals(transaction.execute(status -> dispatchOne()))) break;
        }
    }

    private boolean dispatchOne() {
        var rows = jdbc.query("""
                SELECT event_id, consulta_id, consulta_finalizada_event_id, motivo_codigo, motivo,
                       rechazada_en, attempts
                FROM rechazos_facturacion
                WHERE estado = 'PENDIENTE' AND next_attempt_at <= ?
                ORDER BY next_attempt_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, index) -> new Pending(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                rs.getObject(6, OffsetDateTime.class), rs.getInt(7)), now());
        if (rows.isEmpty()) return false;
        Pending row = rows.getFirst();
        // Also handles an operator lowering the configured limit; never increment an exhausted counter.
        int attempts = row.attempts() >= settings.maxAttempts() ? row.attempts() : row.attempts() + 1;
        String payload = null;
        String error = UNCONFIRMED;
        try {
            payload = json.writeValueAsString(Map.of("eventId", row.id().toString(),
                    "consultaId", row.consulta().toString(), "consultaFinalizadaEventId", row.closure().toString(),
                    "motivoCodigo", row.code(), "motivo", row.reason(),
                    "rechazadaEn", row.rejectedAt().toInstant().toString()));
        } catch (RuntimeException encodingFailure) {
            // Only encoding is caught: SQL and audit failures must escape and roll back this transaction.
            error = ENCODING_FAILED;
        }
        boolean delivered = row.attempts() < settings.maxAttempts() && payload != null
                && sender.send(row.id(), exchange, routingKey, payload);
        boolean terminal = !delivered && attempts >= settings.maxAttempts();
        var updatedAt = now();
        jdbc.update("""
                UPDATE rechazos_facturacion
                SET attempts = ?, estado = ?, sent_at = ?, next_attempt_at = ?, last_error = ?
                WHERE event_id = ?
                """, attempts, delivered ? "ENVIADO" : terminal ? "FALLIDO" : "PENDIENTE",
                delivered ? updatedAt : null,
                terminal || delivered ? updatedAt : updatedAt.plusNanos(settings.backoffMs(attempts) * 1_000_000),
                delivered ? null : error, row.id());
        if (terminal) {
            auditoria.registrar("FACTURACION_RECHAZADA_PUBLICACION_FALLIDA", "rechazos_facturacion", row.id(),
                    Map.of("consultaFinalizadaEventId", row.closure(), "attempts", attempts,
                            "estado", "FALLIDO", "motivo", error));
        }
        return true;
    }

    private OffsetDateTime now() { return clock.instant().atOffset(ZoneOffset.UTC); }

    private record Pending(UUID id, UUID consulta, UUID closure, String code, String reason,
                           OffsetDateTime rejectedAt, int attempts) { }
}
