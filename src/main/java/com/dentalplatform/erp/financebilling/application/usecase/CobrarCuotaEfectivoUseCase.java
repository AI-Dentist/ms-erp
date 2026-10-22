package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.financebilling.application.command.CobrarEfectivoCommand;
import com.dentalplatform.erp.financebilling.domain.model.MetodoPago;
import com.dentalplatform.erp.financebilling.domain.model.Recibo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CobrarCuotaEfectivoUseCase {
    private final RegistrarCobroCuotaService registrar;

    public CobrarCuotaEfectivoUseCase(RegistrarCobroCuotaService registrar) { this.registrar = registrar; }

    @Transactional
    public Recibo ejecutar(CobrarEfectivoCommand c) {
        return registrar.registrar(c.cuotaId(), c.usuarioCajeroId(), MetodoPago.EFECTIVO, c.ciNitCliente(), c.razonSocialCliente());
    }
}
