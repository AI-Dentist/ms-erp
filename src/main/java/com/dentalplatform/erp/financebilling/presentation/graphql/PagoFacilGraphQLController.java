package com.dentalplatform.erp.financebilling.presentation.graphql;

import com.dentalplatform.erp.financebilling.application.port.PagoFacilGatewayPort;
import com.dentalplatform.erp.financebilling.application.usecase.*;
import com.dentalplatform.erp.financebilling.domain.model.*;
import com.dentalplatform.erp.financebilling.domain.repository.TransaccionQrRepository;
import com.dentalplatform.erp.shared.domain.exception.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.graphql.data.method.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

@Controller
public class PagoFacilGraphQLController {
    private final GenerarQrPagoFacilUseCase generate;
    private final ConfirmarPagoQrWebhookUseCase confirm;
    private final TransaccionQrRepository transactions;
    private final PagoFacilGatewayPort gateway;
    private final Environment environment;
    private final boolean simulation;
    private final Clock clock;

    public PagoFacilGraphQLController(GenerarQrPagoFacilUseCase generate, ConfirmarPagoQrWebhookUseCase confirm,
                                      TransaccionQrRepository transactions, PagoFacilGatewayPort gateway,
                                      Environment environment, Clock clock,
                                      @Value("${erp.pagofacil.dev-simulation-enabled:false}") boolean simulation) {
        this.generate = generate;
        this.confirm = confirm;
        this.transactions = transactions;
        this.gateway = gateway;
        this.environment = environment;
        this.clock = clock;
        this.simulation = simulation;
    }

    @MutationMapping
    @PreAuthorize("hasAnyRole('ADMIN','CAJERO')")
    public TransaccionQr generarQrPagoFacil(@Argument UUID cuotaId, @Argument String ciNitCliente,
                                            @Argument String razonSocialCliente) {
        return generate.ejecutar(cuotaId, ciNitCliente, razonSocialCliente);
    }

    @MutationMapping
    @PreAuthorize("hasAnyRole('ADMIN','CAJERO')")
    public Recibo simularConfirmacionQrDev(@Argument String pagofacilTransactionId) {
        gateway.exigirMock();
        if (!simulation || !environment.acceptsProfiles(Profiles.of("dev"))) {
            throw new BusinessRuleException("La simulación QR solo está habilitada en el perfil dev");
        }
        var transaction = transactions.buscar(pagofacilTransactionId)
                .orElseThrow(() -> new ResourceNotFoundException("La transacción QR no existe"));
        return confirm.ejecutar(pagofacilTransactionId, transaction.monto().amount(), OffsetDateTime.now(clock));
    }

}
