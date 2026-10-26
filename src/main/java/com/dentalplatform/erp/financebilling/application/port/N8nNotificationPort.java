package com.dentalplatform.erp.financebilling.application.port;

import com.dentalplatform.erp.financebilling.domain.model.Recibo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public interface N8nNotificationPort {
    /** Best-effort notification after commit. Adapters must not disclose patient identity. */
    void notificar(Recibo receipt);

    /** Whether the n8n integration is switched on; when false no notification is ever sent. */
    boolean habilitado();

    /**
     * Sends one WhatsApp collection reminder to the n8n workflow. Unlike receipts this carries the contact data the
     * workflow needs to message the patient, so it is only invoked by the authorized collection use case.
     * Throws when n8n does not accept the delivery.
     */
    void notificarCobranza(CobranzaAlerta alerta);

    record CobranzaAlerta(UUID cuotaId, UUID ordenCobroId, UUID pacienteId, String nombrePaciente,
                          String telefonoWhatsapp, int numeroCuota, BigDecimal montoBs, LocalDate vencimiento,
                          long diasAtraso, QrPendiente qr) { }

    /** The unexpired PagoFácil QR of the installment, when one was already generated. */
    record QrPendiente(String transaccionId, OffsetDateTime expiraEn, String imagenBase64) { }
}
