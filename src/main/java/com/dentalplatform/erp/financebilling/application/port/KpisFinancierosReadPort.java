package com.dentalplatform.erp.financebilling.application.port;

import com.dentalplatform.erp.financebilling.domain.model.MetodoPago;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface KpisFinancierosReadPort {

    record KpiSerieDia(LocalDate fecha, BigDecimal montoBs) {}

    record IngresosData(BigDecimal totalBs, int cantidadMovimientos, BigDecimal montoNominalQrBs,
                        List<KpiSerieDia> seriePorDia) {}

    record KpiCuotaVencida(UUID cuotaId, UUID ordenCobroId, UUID pacienteId, String pacienteNombre,
                           int numeroCuota, BigDecimal montoBs, LocalDate fechaVencimiento, int diasAtraso) {}

    record MorosidadData(int cuotasExigibles, int cuotasVencidas, BigDecimal montoVencidoBs,
                         List<KpiCuotaVencida> detalle) {}

    record KpiServicioRanking(UUID servicioId, String nombre, int cantidad, BigDecimal montoBs) {}

    record KpiFlujoDia(LocalDate fecha, BigDecimal ingresosBs, BigDecimal egresosBs, BigDecimal saldoBs) {}

    record CobranzaMetodoRow(MetodoPago metodoPago, int cantidad, BigDecimal montoBs) {}

    IngresosData consultarIngresos(OffsetDateTime inicio, OffsetDateTime fin);

    MorosidadData consultarMorosidad(LocalDate startDate, LocalDate endDate, LocalDate evalDate);

    List<KpiServicioRanking> consultarServiciosMasSolicitados(OffsetDateTime inicio, OffsetDateTime fin, int limit);

    List<KpiFlujoDia> consultarFlujoSerieDia(OffsetDateTime inicio, OffsetDateTime fin);

    List<CobranzaMetodoRow> consultarCobranzaPorMetodo(OffsetDateTime inicio, OffsetDateTime fin);
}
