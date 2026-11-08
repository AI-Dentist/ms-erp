package com.dentalplatform.erp.financebilling.application.usecase;

/** Definitive rejection with a fixed, non-personal operator message. */
public class RechazoFacturacionException extends RuntimeException {
    public enum Codigo {
        PACIENTE_INEXISTENTE("El paciente indicado no existe."),
        ODONTOLOGO_INEXISTENTE("El odontólogo indicado no existe."),
        SERVICIO_INEXISTENTE("Un servicio indicado no existe."),
        SERVICIO_INACTIVO("Un servicio indicado está inactivo."),
        MONTO_INVALIDO("El monto indicado no es válido."),
        PAYLOAD_INVALIDO("Los datos de facturación no son válidos."),
        REINTENTOS_AGOTADOS("Se agotaron los reintentos de facturación.");

        private final String motivo;

        Codigo(String motivo) { this.motivo = motivo; }

        public String motivo() { return motivo; }
    }

    private final Codigo codigo;

    public RechazoFacturacionException(Codigo codigo) {
        super(codigo.motivo());
        this.codigo = codigo;
    }

    public Codigo codigo() { return codigo; }
}
