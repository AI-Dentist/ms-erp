package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.financebilling.application.agent.AgentActorResolver;
import com.dentalplatform.erp.financebilling.application.port.CobranzaRecordatorioPort;
import com.dentalplatform.erp.financebilling.application.port.MonitoreoNegocioPort.CuotaVencida;
import com.dentalplatform.erp.financebilling.application.port.N8nNotificationPort;
import com.dentalplatform.erp.financebilling.application.port.N8nNotificationPort.CobranzaAlerta;
import com.dentalplatform.erp.financebilling.application.port.N8nNotificationPort.QrPendiente;
import com.dentalplatform.erp.financebilling.domain.repository.TransaccionQrRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sends WhatsApp collection reminders through n8n for overdue installments. Bounded to {@value #MAX_PER_CALL}
 * reminders per call and never repeats the same installment inside {@code 24h} (ledger: cobranza_recordatorios).
 * Deliberately not one big transaction: each ledger/audit step commits on its own so an n8n timeout never holds a
 * database transaction, and a failed delivery releases its claim so it can be retried.
 */
@Service
public class DispararAlertaCobranzaUseCase {
    public static final int MAX_PER_CALL = 20;
    public static final Duration DEDUP_WINDOW = Duration.ofHours(24);
    private static final Logger log = LoggerFactory.getLogger(DispararAlertaCobranzaUseCase.class);

    private final MonitoreoNegocioUseCase monitoring;
    private final N8nNotificationPort n8n;
    private final CobranzaRecordatorioPort ledger;
    private final TransaccionQrRepository qrs;
    private final RegistrarAuditoriaUseCase auditoria;
    private final AgentActorResolver actor;
    private final Clock clock;

    public DispararAlertaCobranzaUseCase(MonitoreoNegocioUseCase monitoring, N8nNotificationPort n8n,
                                         CobranzaRecordatorioPort ledger, TransaccionQrRepository qrs,
                                         RegistrarAuditoriaUseCase auditoria, AgentActorResolver actor, Clock clock) {
        this.monitoring = monitoring;
        this.n8n = n8n;
        this.ledger = ledger;
        this.qrs = qrs;
        this.auditoria = auditoria;
        this.actor = actor;
        this.clock = clock;
    }

    /** @param cuotaIds installments to remind; null or empty means "the overdue ones, oldest first". */
    @PreAuthorize("hasAnyRole('ADMIN','CAJERO')")
    public Resultado ejecutar(List<UUID> cuotaIds) {
        if (!n8n.habilitado()) {
            return new Resultado(false, 0, 0, 0, 0, List.of(),
                    "n8n está deshabilitado en este entorno; no se envió ningún recordatorio");
        }
        var overdue = monitoring.cuotasVencidas(LocalDate.now(clock));
        Set<UUID> requested = cuotaIds == null ? Set.of() : Set.copyOf(cuotaIds);
        List<CuotaVencida> candidates = requested.isEmpty() ? overdue
                : overdue.stream().filter(c -> requested.contains(c.cuotaId())).toList();
        int notEligible = requested.isEmpty() ? 0 : requested.size() - candidates.size();
        List<UUID> sent = new ArrayList<>();
        int duplicates = 0, failed = 0, handled = 0;
        for (CuotaVencida due : candidates) {
            if (handled >= MAX_PER_CALL) { break; }
            handled++;
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (!ledger.reservar(due.cuotaId(), actor.usuarioId().orElse(null), actor.solicitante(), now, DEDUP_WINDOW)) {
                duplicates++;
                continue;
            }
            try {
                n8n.notificarCobranza(alert(due, now));
                auditoria.registrar("COBRANZA_RECORDATORIO_ENVIADO", "cuotas_pago", due.cuotaId(), Map.of());
                sent.add(due.cuotaId());
            } catch (RuntimeException e) {
                ledger.liberar(due.cuotaId(), now);
                failed++;
                log.warn("n8n collection reminder failed for an installment: {}", e.getClass().getSimpleName());
            }
        }
        int pending = Math.max(0, candidates.size() - handled);
        String reason = sent.isEmpty() ? motivoSinEnvio(candidates.size(), duplicates, failed, notEligible)
                : pending > 0 ? "Se alcanzó el máximo de " + MAX_PER_CALL + " recordatorios por llamada; quedan "
                        + pending + " cuota(s) por recordar" : null;
        return new Resultado(!sent.isEmpty(), sent.size(), duplicates, failed, notEligible, List.copyOf(sent), reason);
    }

    private CobranzaAlerta alert(CuotaVencida due, OffsetDateTime now) {
        QrPendiente qr = qrs.buscarVigentePorCuota(due.cuotaId(), now)
                .map(t -> new QrPendiente(t.pagofacilTransactionId(), t.fechaExpiracion(), t.qrImagenBase64()))
                .orElse(null);
        return new CobranzaAlerta(due.cuotaId(), due.ordenId(), due.pacienteId(), due.nombreCompleto(),
                due.telefonoWhatsapp(), due.numeroCuota(), due.montoBs(), due.vencimiento(), due.diasAtraso(), qr);
    }

    private static String motivoSinEnvio(int candidates, int duplicates, int failed, int notEligible) {
        if (failed > 0) { return "n8n no aceptó " + failed + " recordatorio(s); puede reintentarlos"; }
        if (duplicates > 0) { return "Ya se envió un recordatorio de estas cuotas en las últimas 24 horas"; }
        if (notEligible > 0) { return "Las cuotas indicadas no están vencidas o no existen"; }
        return candidates == 0 ? "No hay cuotas vencidas para recordar" : "No se envió ningún recordatorio";
    }

    /** Never includes patient names, phones or QR data: it is handed to the model and to the history. */
    public record Resultado(boolean notificacionEnviada, int enviados, int omitidosPorDuplicado, int fallidos,
                            int noElegibles, List<UUID> cuotasNotificadas, String motivo) { }
}
