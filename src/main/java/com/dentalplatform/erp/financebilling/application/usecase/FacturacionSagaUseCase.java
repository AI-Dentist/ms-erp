package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.financebilling.application.command.ConsultaFinalizadaCommand;
import com.dentalplatform.erp.financebilling.application.port.RechazoFacturacionPort;
import com.dentalplatform.erp.financebilling.domain.repository.OrdenCobroRepository;
import com.dentalplatform.erp.mastercatalog.application.usecase.ConsultarCatalogoUseCase;
import com.dentalplatform.erp.mastercatalog.domain.repository.ServicioOdontologicoRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.vo.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dentalplatform.erp.financebilling.application.usecase.RechazoFacturacionException.Codigo.*;

@Service
public class FacturacionSagaUseCase {
    private static final BigDecimal MAX_MONTO = new BigDecimal("9999999999.99");
    private final OrdenCobroRepository orders;
    private final RechazoFacturacionPort rechazos;
    private final ConsultarCatalogoUseCase catalogo;
    private final ServicioOdontologicoRepository servicios;
    private final ProcesarConsultaFinalizadaUseCase procesar;
    private final RegistrarAuditoriaUseCase auditoria;
    private final Clock clock;

    public FacturacionSagaUseCase(OrdenCobroRepository orders, RechazoFacturacionPort rechazos,
                                 ConsultarCatalogoUseCase catalogo, ServicioOdontologicoRepository servicios,
                                 ProcesarConsultaFinalizadaUseCase procesar, RegistrarAuditoriaUseCase auditoria,
                                 Clock clock) {
        this.orders = orders;
        this.rechazos = rechazos;
        this.catalogo = catalogo;
        this.servicios = servicios;
        this.procesar = procesar;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    @Transactional
    public void procesar(UUID consulta, UUID closure, Supplier<ConsultaFinalizadaCommand> supplier) {
        orders.bloquearConsulta(consulta);
        if (orders.existeConsulta(consulta) || rechazos.existePorCierre(closure)) { return; }
        var command = supplier.get();
        if (command == null || !consulta.equals(command.consultaId())) {
            throw new RechazoFacturacionException(PAYLOAD_INVALIDO);
        }
        validar(command);
        // Existing processor proxy joins this transaction; the consultation lock remains held.
        procesar.ejecutar(command);
    }

    private void validar(ConsultaFinalizadaCommand c) {
        if (c.pacienteId() == null) { throw new RechazoFacturacionException(PAYLOAD_INVALIDO); }
        if (catalogo.obtenerPaciente(c.pacienteId()).isEmpty()) {
            throw new RechazoFacturacionException(PACIENTE_INEXISTENTE);
        }
        if (c.odontologoId() != null && catalogo.listarOdontologos(false).stream()
                .noneMatch(o -> o.getId().equals(c.odontologoId()))) {
            throw new RechazoFacturacionException(ODONTOLOGO_INEXISTENTE);
        }
        if (c.serviciosRealizados() == null || c.serviciosRealizados().isEmpty()) {
            throw new RechazoFacturacionException(PAYLOAD_INVALIDO);
        }
        Money total = Money.zero();
        for (var item : c.serviciosRealizados()) {
            if (item == null || item.servicioId() == null) {
                throw new RechazoFacturacionException(PAYLOAD_INVALIDO);
            }
            var servicio = servicios.buscarPorId(item.servicioId())
                    .orElseThrow(() -> new RechazoFacturacionException(SERVICIO_INEXISTENTE));
            if (!servicio.isActivo()) { throw new RechazoFacturacionException(SERVICIO_INACTIVO); }
            Money precio;
            try {
                // Reuse Money's HALF_UP semantics, including acceptance of -0.004 as zero.
                precio = Money.of(item.precioUnitarioBs());
            } catch (BusinessRuleException | ArithmeticException invalid) {
                throw new RechazoFacturacionException(MONTO_INVALIDO);
            }
            if (item.cantidad() <= 0 || (item.piezaDental() != null && item.piezaDental().length() > 10)) {
                throw new RechazoFacturacionException(PAYLOAD_INVALIDO);
            }
            total = total.plus(precio.times(item.cantidad()));
            if (precio.amount().compareTo(MAX_MONTO) > 0 || total.amount().compareTo(MAX_MONTO) > 0) {
                throw new RechazoFacturacionException(MONTO_INVALIDO);
            }
        }
    }

    /** Invoked only after the failed billing proxy invocation has finished rolling back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrarRechazo(UUID consulta, UUID closure, String codigo) {
        orders.bloquearConsulta(consulta);
        if (orders.existeConsulta(consulta)) { return; }
        var reason = RechazoFacturacionException.Codigo.valueOf(codigo);
        UUID eventId;
        do {
            eventId = UUID.randomUUID();
        } while (eventId.equals(consulta) || eventId.equals(closure));
        var rechazo = new RechazoFacturacionPort.Rechazo(eventId, consulta, closure, reason.name(),
                reason.motivo(), OffsetDateTime.now(clock));
        if (rechazos.insertarSiAusente(rechazo)) {
            auditoria.registrar("FACTURACION_CONSULTA_RECHAZADA", "rechazos_facturacion", eventId,
                    Map.of("consultaId", consulta, "consultaFinalizadaEventId", closure,
                            "motivoCodigo", reason.name()));
        }
    }
}
