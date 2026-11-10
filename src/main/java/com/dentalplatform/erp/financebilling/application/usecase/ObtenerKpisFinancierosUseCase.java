package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.financebilling.application.port.KpisFinancierosReadPort;
import com.dentalplatform.erp.financebilling.domain.model.MetodoPago;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
// One REPEATABLE READ snapshot so every KPI in a response is computed from the same data.
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ObtenerKpisFinancierosUseCase {

    private static final ZoneId ZONE_LA_PAZ = ZoneId.of("America/La_Paz");
    private static final Duration MAX_PERIOD = Duration.ofDays(366);

    private final KpisFinancierosReadPort readPort;
    private final ConsultarFinanzasUseCase consultarFinanzas;
    private final Clock clock;

    public ObtenerKpisFinancierosUseCase(KpisFinancierosReadPort readPort,
                                         ConsultarFinanzasUseCase consultarFinanzas,
                                         Clock clock) {
        this.readPort = readPort;
        this.consultarFinanzas = consultarFinanzas;
        this.clock = clock;
    }

    public record KpisFinancieros(
            OffsetDateTime fechaInicio,
            OffsetDateTime fechaFin,
            KpiIngresos ingresos,
            KpiMorosidad morosidad,
            List<KpisFinancierosReadPort.KpiServicioRanking> serviciosMasSolicitados,
            KpiFlujoCaja flujoCaja,
            List<KpiCobranzaMetodo> cobranzaPorMetodo
    ) {}

    public record KpiIngresos(
            BigDecimal totalBs,
            int cantidadMovimientos,
            BigDecimal montoNominalQrBs,
            List<KpisFinancierosReadPort.KpiSerieDia> seriePorDia
    ) {}

    public record KpiMorosidad(
            int cuotasVencidas,
            int cuotasExigibles,
            double porcentaje,
            BigDecimal montoVencidoBs,
            List<KpisFinancierosReadPort.KpiCuotaVencida> detalle
    ) {}

    public record KpiFlujoCaja(
            BigDecimal totalIngresosBs,
            BigDecimal totalEgresosBs,
            BigDecimal saldoNetoBs,
            List<KpisFinancierosReadPort.KpiFlujoDia> seriePorDia
    ) {}

    public record KpiCobranzaMetodo(
            MetodoPago metodoPago,
            int cantidad,
            BigDecimal montoBs,
            double porcentajeMonto,
            boolean montoNominal
    ) {}

    public KpisFinancieros ejecutar(OffsetDateTime inicio, OffsetDateTime fin) {
        if (inicio == null || fin == null || inicio.isAfter(fin)) {
            throw new BusinessRuleException("El rango de fechas es inválido: fechaInicio no puede ser posterior a fechaFin");
        }

        if (Duration.between(inicio, fin).compareTo(MAX_PERIOD) > 0) {
            throw new BusinessRuleException("El período no puede exceder los 366 días");
        }
        LocalDate startLocalDate = inicio.atZoneSameInstant(ZONE_LA_PAZ).toLocalDate();
        LocalDate endLocalDate = fin.atZoneSameInstant(ZONE_LA_PAZ).toLocalDate();

        // 1. Ingresos
        var ingresosData = readPort.consultarIngresos(inicio, fin);
        var ingresos = new KpiIngresos(
                ingresosData.totalBs(),
                ingresosData.cantidadMovimientos(),
                ingresosData.montoNominalQrBs(),
                ingresosData.seriePorDia()
        );

        // 2. Morosidad evaluated at min(fechaFin, now) in America/La_Paz
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime evalDateTime = fin.isBefore(now) ? fin : now;
        LocalDate evalDate = evalDateTime.atZoneSameInstant(ZONE_LA_PAZ).toLocalDate();

        var morosidadData = readPort.consultarMorosidad(startLocalDate, endLocalDate, evalDate);
        double morosidadPorcentaje = morosidadData.cuotasExigibles() == 0
                ? 0.0
                : BigDecimal.valueOf(morosidadData.cuotasVencidas())
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(morosidadData.cuotasExigibles()), 2, RoundingMode.HALF_UP)
                .doubleValue();

        var morosidad = new KpiMorosidad(
                morosidadData.cuotasVencidas(),
                morosidadData.cuotasExigibles(),
                morosidadPorcentaje,
                morosidadData.montoVencidoBs(),
                morosidadData.detalle()
        );

        // 3. Servicios más solicitados (top 10)
        var serviciosMasSolicitados = readPort.consultarServiciosMasSolicitados(inicio, fin, 10);

        // 4. Flujo de caja (reuse ConsultarFinanzasUseCase totals for exact consistency)
        var resumenFlujo = consultarFinanzas.obtenerFlujoCaja(inicio, fin);
        var flujoSerieDia = readPort.consultarFlujoSerieDia(inicio, fin);
        var flujoCaja = new KpiFlujoCaja(
                resumenFlujo.totalIngresosBs(),
                resumenFlujo.totalEgresosBs(),
                resumenFlujo.saldoNetoBs(),
                flujoSerieDia
        );

        // 5. Cobranza por método (all MetodoPago values present, nominal flag, percentage of total income)
        var cobranzaRows = readPort.consultarCobranzaPorMetodo(inicio, fin);
        Map<MetodoPago, KpisFinancierosReadPort.CobranzaMetodoRow> map = new EnumMap<>(MetodoPago.class);
        for (var row : cobranzaRows) {
            if (row.metodoPago() != null) {
                map.put(row.metodoPago(), row);
            }
        }

        BigDecimal totalIngresos = resumenFlujo.totalIngresosBs();
        List<KpiCobranzaMetodo> cobranzaList = new ArrayList<>();
        for (MetodoPago mp : MetodoPago.values()) {
            var row = map.get(mp);
            int cantidad = row != null ? row.cantidad() : 0;
            BigDecimal montoBs = row != null ? row.montoBs() : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            boolean montoNominal = (mp == MetodoPago.QR_PAGOFACIL);
            double porcentajeMonto = totalIngresos.signum() == 0
                    ? 0.0
                    : montoBs.multiply(BigDecimal.valueOf(100))
                    .divide(totalIngresos, 2, RoundingMode.HALF_UP)
                    .doubleValue();

            cobranzaList.add(new KpiCobranzaMetodo(mp, cantidad, montoBs, porcentajeMonto, montoNominal));
        }

        return new KpisFinancieros(
                inicio,
                fin,
                ingresos,
                morosidad,
                serviciosMasSolicitados,
                flujoCaja,
                cobranzaList
        );
    }
}
