package com.dentalplatform.erp.mastercatalog.application.port;

import java.util.UUID;

/**
 * Outgoing port to the billing data: unpaid charge orders block the deactivation of a patient.
 */
public interface OrdenesAbiertasPacientePort {

    /** Orders of the patient in state PENDIENTE or PARCIAL. */
    long contar(UUID pacienteId);
}
