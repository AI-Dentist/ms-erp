package com.dentalplatform.erp.financebilling.application.report.manual;

import java.util.List;
import java.util.Optional;

/**
 * The only entities and columns the manual report builder accepts. Keys are server constants: client strings are
 * matched against them and never reach SQL. Foreign technical ids are not exposed; names replace them.
 */
public final class CatalogoReporteManual {
    public static final String TEXTO = "TEXTO", NUMERO = "NUMERO", MONEDA_BS = "MONEDA_BS", FECHA = "FECHA",
            FECHA_HORA = "FECHA_HORA", BOOLEANO = "BOOLEANO";

    private static final List<EntidadReporte> ENTIDADES = List.of(
            new EntidadReporte("citas", "Reservas / Citas", "MS3", List.of(
                    col("citaId", "ID Cita", TEXTO),
                    col("pacienteNombre", "Nombre Paciente", TEXTO),
                    col("odontologoNombre", "Odontólogo", TEXTO),
                    col("consultorioNombre", "Consultorio", TEXTO),
                    col("fechaHora", "Fecha y Hora", FECHA_HORA),
                    col("duracionMin", "Duración (min)", NUMERO),
                    col("tipoTratamiento", "Tipo de tratamiento", TEXTO),
                    col("estado", "Estado", TEXTO),
                    col("reservadaEn", "Reservada en", FECHA_HORA),
                    col("probabilidadNoShow", "Probabilidad No-Show", NUMERO),
                    col("nivelRiesgo", "Riesgo No-Show", TEXTO)),
                    List.of("RESERVADA", "EN_ATENCION", "COMPLETADA", "CANCELADA", "REPROGRAMADA", "NO_ASISTIO"), true),
            new EntidadReporte("pacientes", "Pacientes", "MS1", List.of(
                    col("id", "ID Paciente", TEXTO),
                    col("ciNit", "CI/NIT", TEXTO),
                    col("nombres", "Nombres", TEXTO),
                    col("apellidos", "Apellidos", TEXTO),
                    col("fechaNacimiento", "Fecha de nacimiento", FECHA),
                    col("genero", "Género", TEXTO),
                    col("telefonoWhatsapp", "Teléfono WhatsApp", TEXTO),
                    col("email", "Email", TEXTO),
                    col("direccion", "Dirección", TEXTO),
                    col("activo", "Activo", BOOLEANO),
                    col("fechaRegistro", "Fecha de registro", FECHA_HORA)),
                    List.of("ACTIVO", "INACTIVO"), true),
            new EntidadReporte("cuotas", "Planes de Pago y Cuotas", "MS1", List.of(
                    col("id", "ID Cuota", TEXTO),
                    col("paciente", "Paciente", TEXTO),
                    col("ciNitPaciente", "CI/NIT paciente", TEXTO),
                    col("numeroCuota", "Nº de cuota", NUMERO),
                    col("montoCuotaBs", "Monto de la cuota (Bs)", MONEDA_BS),
                    col("fechaVencimiento", "Fecha de vencimiento", FECHA),
                    col("estado", "Estado", TEXTO),
                    col("fechaPago", "Fecha de pago", FECHA_HORA),
                    col("modalidadPago", "Modalidad de pago", TEXTO),
                    col("totalOrdenBs", "Total de la orden (Bs)", MONEDA_BS),
                    col("saldoOrdenBs", "Saldo pendiente de la orden (Bs)", MONEDA_BS),
                    col("estadoOrden", "Estado de la orden", TEXTO)),
                    List.of("PENDIENTE", "PAGADA", "VENCIDA", "ANULADA"), true),
            new EntidadReporte("pagos", "Pagos y Recibos", "MS1", List.of(
                    col("id", "ID Pago", TEXTO),
                    col("fechaHora", "Fecha y hora", FECHA_HORA),
                    col("metodoPago", "Método de pago", TEXTO),
                    col("categoria", "Categoría", TEXTO),
                    col("montoBs", "Monto (Bs; en QR es monto nominal)", MONEDA_BS),
                    col("montoNominal", "Monto nominal QR (demo PagoFácil cobra /1000)", BOOLEANO),
                    col("numeroRecibo", "Nº de recibo", TEXTO),
                    col("ciNitCliente", "CI/NIT cliente", TEXTO),
                    col("razonSocialCliente", "Razón social", TEXTO),
                    col("concepto", "Concepto", TEXTO),
                    col("descripcion", "Descripción", TEXTO),
                    col("cajero", "Cajero", TEXTO)),
                    List.of(), true),
            new EntidadReporte("egresos", "Egresos de Caja Chica", "MS1", List.of(
                    col("id", "ID Egreso", TEXTO),
                    col("fechaHora", "Fecha y hora", FECHA_HORA),
                    col("categoria", "Categoría", TEXTO),
                    col("metodoPago", "Método de pago", TEXTO),
                    col("montoBs", "Monto (Bs)", MONEDA_BS),
                    col("descripcion", "Descripción", TEXTO),
                    col("cajero", "Registrado por", TEXTO)),
                    List.of(), true),
            new EntidadReporte("servicios", "Catálogo de Servicios (Arancel)", "MS1", List.of(
                    col("id", "ID Servicio", TEXTO),
                    col("codigo", "Código", TEXTO),
                    col("nombre", "Nombre", TEXTO),
                    col("categoria", "Categoría", TEXTO),
                    col("especialidad", "Especialidad", TEXTO),
                    col("descripcion", "Descripción", TEXTO),
                    col("precioBaseBs", "Precio base (Bs)", MONEDA_BS),
                    col("activo", "Activo", BOOLEANO)),
                    List.of("ACTIVO", "INACTIVO"), false),
            new EntidadReporte("insumos", "Inventario / Stock de Insumos", "MS3", List.of(
                    col("insumoId", "ID Insumo", TEXTO),
                    col("nombre", "Insumo", TEXTO),
                    col("unidad", "Unidad", TEXTO),
                    col("stockActual", "Stock actual", NUMERO),
                    col("stockMinimo", "Stock mínimo", NUMERO),
                    col("bajoMinimo", "Bajo mínimo", BOOLEANO),
                    col("salidasPeriodo", "Salidas en el período", NUMERO)),
                    List.of(), true));

    private CatalogoReporteManual() { }

    public static List<EntidadReporte> entidades() { return ENTIDADES; }

    public static Optional<EntidadReporte> buscar(String clave) {
        return ENTIDADES.stream().filter(e -> e.clave().equals(clave)).findFirst();
    }

    private static ColumnaReporte col(String clave, String etiqueta, String tipo) {
        return new ColumnaReporte(clave, etiqueta, tipo);
    }
}
