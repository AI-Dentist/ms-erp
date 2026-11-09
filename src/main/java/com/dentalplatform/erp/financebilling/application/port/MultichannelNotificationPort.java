package com.dentalplatform.erp.financebilling.application.port;

import java.math.BigDecimal;
import java.util.List;

/** Separate S17 transport: legacy receipt/overdue-reminder delivery semantics are deliberately unchanged. */
public interface MultichannelNotificationPort {
    boolean habilitado();
    void enviar(Payload payload);

    record Payload(String tipoEvento, List<String> canalesActivos, Destinatario destinatario,
                   DatosFinancieros datosFinancieros, String mensajeFormateado) { }
    record Destinatario(String nombre, String telefono, String email, String telegramChatId) { }
    record DatosFinancieros(String moneda, String simbolo, BigDecimal monto, String concepto, String fechaVencimiento) { }

    /** A network failure, not an HTTP rejection: only this failure is eligible for demo simulation. */
    final class UnavailableException extends RuntimeException {
        public UnavailableException(Throwable cause) { super("Notification transport unavailable", cause); }
    }
}
