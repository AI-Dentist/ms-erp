package com.dentalplatform.erp.financebilling.presentation.graphql;

import com.dentalplatform.erp.financebilling.application.command.*;
import com.dentalplatform.erp.financebilling.application.usecase.*;
import com.dentalplatform.erp.financebilling.domain.model.*;
import com.dentalplatform.erp.mastercatalog.application.usecase.ConsultarCatalogoUseCase;
import com.dentalplatform.erp.mastercatalog.domain.model.Paciente;
import com.dentalplatform.erp.mastercatalog.domain.model.Odontologo;
import com.dentalplatform.erp.mastercatalog.domain.model.ServicioOdontologico;
import org.springframework.graphql.data.method.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Controller
public class FinanceBillingGraphQLController {
    private static final String READ_OR_PLAN = "hasAnyRole('ADMIN','CAJERO','RECEPCIONISTA')";
    private static final String CASH = "hasAnyRole('ADMIN','CAJERO')";
    private final ConsultarFinanzasUseCase consultar;
    private final ConsultarCatalogoUseCase catalogo;
    private final CrearOrdenCobroManualUseCase crear;
    private final ConfigurarPlanPagosUseCase plan;
    private final CobrarCuotaEfectivoUseCase cobrar;
    private final RegistrarEgresoCajaUseCase egreso;

    public FinanceBillingGraphQLController(ConsultarFinanzasUseCase consultar, ConsultarCatalogoUseCase catalogo,
                                           CrearOrdenCobroManualUseCase crear, ConfigurarPlanPagosUseCase plan,
                                           CobrarCuotaEfectivoUseCase cobrar, RegistrarEgresoCajaUseCase egreso) {
        this.consultar = consultar;
        this.catalogo = catalogo;
        this.crear = crear;
        this.plan = plan;
        this.cobrar = cobrar;
        this.egreso = egreso;
    }

    @QueryMapping
    @PreAuthorize(READ_OR_PLAN)
    public List<OrdenCobro> listarOrdenesCobro(@Argument EstadoOrdenCobro estado, @Argument UUID pacienteId) {
        return consultar.listarOrdenesCobro(estado, pacienteId);
    }

    @QueryMapping
    @PreAuthorize(READ_OR_PLAN)
    public OrdenCobro obtenerOrdenCobroDetalle(@Argument UUID id) {
        return consultar.obtenerOrdenCobroDetalle(id).orElse(null);
    }

    @QueryMapping
    @PreAuthorize(READ_OR_PLAN)
    public List<CuotaPago> listarCuotasPendientesPorPaciente(@Argument UUID pacienteId) {
        return consultar.listarCuotasPendientesPorPaciente(pacienteId);
    }

    @QueryMapping
    @PreAuthorize(READ_OR_PLAN)
    public ConsultarFinanzasUseCase.ResumenFlujoCaja obtenerFlujoCaja(@Argument OffsetDateTime fechaInicio,
                                                                    @Argument OffsetDateTime fechaFin) {
        return consultar.obtenerFlujoCaja(fechaInicio, fechaFin);
    }

    @MutationMapping
    @PreAuthorize(READ_OR_PLAN)
    public OrdenCobro crearOrdenCobroManual(@Argument CrearOrdenCobroCommand input) { return crear.ejecutar(input); }

    @MutationMapping
    @PreAuthorize(READ_OR_PLAN)
    public OrdenCobro configurarPlanDePagos(@Argument ConfigurarPlanPagosCommand input) { return plan.ejecutar(input); }

    @MutationMapping
    @PreAuthorize(CASH)
    public Recibo cobrarCuotaEnEfectivo(@Argument CobrarEfectivoCommand input) { return cobrar.ejecutar(input); }

    @MutationMapping
    @PreAuthorize(CASH)
    public MovimientoCaja registrarEgresoCaja(@Argument RegistrarEgresoCommand input) { return egreso.ejecutar(input); }

    @SchemaMapping(typeName = "OrdenCobro")
    public Paciente paciente(OrdenCobro order) { return catalogo.obtenerPaciente(order.getPacienteId()).orElse(null); }

    @SchemaMapping(typeName = "OrdenCobro")
    public Odontologo odontologo(OrdenCobro order) {
        return order.getOdontologoId() == null ? null : catalogo.listarOdontologos(false).stream()
                .filter(o -> o.getId().equals(order.getOdontologoId())).findFirst().orElse(null);
    }

    @SchemaMapping(typeName = "OrdenCobroItem")
    public ServicioOdontologico servicio(OrdenCobroItem item) {
        return catalogo.listarServicios(null).stream().filter(s -> s.getId().equals(item.servicioId())).findFirst().orElse(null);
    }

    @SchemaMapping(typeName = "OrdenCobro")
    public BigDecimal montoSubtotalBs(OrdenCobro order) { return order.getSubtotal().amount(); }
    @SchemaMapping(typeName = "OrdenCobro")
    public BigDecimal descuentoBs(OrdenCobro order) { return order.getDescuento().amount(); }
    @SchemaMapping(typeName = "OrdenCobro")
    public BigDecimal montoTotalBs(OrdenCobro order) { return order.getTotal().amount(); }
    @SchemaMapping(typeName = "OrdenCobro")
    public BigDecimal saldoPendienteBs(OrdenCobro order) { return order.getSaldoPendiente().amount(); }
    @SchemaMapping(typeName = "OrdenCobroItem")
    public BigDecimal precioUnitarioBs(OrdenCobroItem item) { return item.precioUnitario().amount(); }
    @SchemaMapping(typeName = "OrdenCobroItem")
    public BigDecimal subtotalBs(OrdenCobroItem item) { return item.subtotal().amount(); }
    @SchemaMapping(typeName = "CuotaPago")
    public BigDecimal montoCuotaBs(CuotaPago cuota) { return cuota.montoCuota().amount(); }
    @SchemaMapping(typeName = "MovimientoCaja")
    public BigDecimal montoBs(MovimientoCaja movement) { return movement.monto().amount(); }
    @SchemaMapping(typeName = "MovimientoCaja")
    public Recibo recibo(MovimientoCaja movement) { return consultar.obtenerRecibo(movement.id()).orElse(null); }
    @SchemaMapping(typeName = "Recibo")
    public BigDecimal montoTotalBs(Recibo receipt) { return receipt.montoTotal().amount(); }
}
