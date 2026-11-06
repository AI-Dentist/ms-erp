package com.dentalplatform.erp.offline.infrastructure;

import com.dentalplatform.erp.mastercatalog.application.command.CrearPacienteCommand;
import com.dentalplatform.erp.mastercatalog.application.usecase.CrearPacienteUseCase;
import com.dentalplatform.erp.offline.application.OfflineOperationHandler;
import com.dentalplatform.erp.offline.domain.OfflineOperationConflict;
import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class OfflinePatientOperationHandler implements OfflineOperationHandler {
    private final CrearPacienteUseCase patients;
    @PersistenceContext private EntityManager entityManager;

    public OfflinePatientOperationHandler(CrearPacienteUseCase patients) {
        this.patients = patients;
    }

    @Override public OfflineOperationReceipt.Type type() {
        return OfflineOperationReceipt.Type.PATIENT_CREATE;
    }

    @Override
    public OfflineOperationReceipt.Result execute(Map<String, Object> payload, UUID actorId) {
        try {
            Object birth = payload.get("fechaNacimiento");
            var patient = patients.ejecutar(new CrearPacienteCommand((String) payload.get("ciNit"),
                    (String) payload.get("nombres"), (String) payload.get("apellidos"),
                    birth == null ? null : LocalDate.parse(birth.toString()), (String) payload.get("genero"),
                    (String) payload.get("telefonoWhatsapp"), (String) payload.get("email"),
                    (String) payload.get("direccion")));
            entityManager.flush();
            return new OfflineOperationReceipt.Result(patient.getId(), null, List.of(), null, null);
        } catch (BusinessRuleException rejected) {
            // Translate the existing duplicate rule, not GraphQL error text or a success heuristic.
            if (rejected.getMessage() != null
                    && rejected.getMessage().startsWith("Ya existe un paciente con el CI/NIT ")) {
                throw new OfflineOperationConflict("PATIENT_DUPLICATE");
            }
            throw new OfflineOperationConflict("INVALID_INPUT");
        }
    }
}
