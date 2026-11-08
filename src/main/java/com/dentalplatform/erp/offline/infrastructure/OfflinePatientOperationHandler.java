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
import java.time.format.DateTimeParseException;
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
        // Read the frozen payload defensively: a malformed value is an explicit conflict, never a technical
        // failure that leaves the intent retryable, and never an unchecked cast escaping this handler.
        String ciNit = required(payload.get("ciNit"));
        String nombres = required(payload.get("nombres"));
        String apellidos = required(payload.get("apellidos"));
        String telefono = required(payload.get("telefonoWhatsapp"));
        LocalDate fechaNacimiento = fechaNacimiento(payload.get("fechaNacimiento"));
        String genero = optional(payload.get("genero"));
        String email = optional(payload.get("email"));
        String direccion = optional(payload.get("direccion"));
        try {
            var patient = patients.ejecutar(new CrearPacienteCommand(ciNit, nombres, apellidos, fechaNacimiento,
                    genero, telefono, email, direccion));
            entityManager.flush();
            return new OfflineOperationReceipt.Result(patient.getId(), null, List.of(), null, null);
        } catch (BusinessRuleException rejected) {
            // Classify on the stable rule code, never on localized message text.
            if (BusinessRuleException.PATIENT_CI_NIT_DUPLICATE.equals(rejected.code())) {
                throw new OfflineOperationConflict("PATIENT_DUPLICATE");
            }
            throw new OfflineOperationConflict("INVALID_INPUT");
        } catch (ClassCastException | IllegalArgumentException rejected) {
            throw new OfflineOperationConflict("INVALID_INPUT");
        }
    }

    private static String required(Object value) {
        if (!(value instanceof String text) || text.isBlank()) throw invalid();
        return text;
    }

    private static String optional(Object value) {
        if (value == null) return null;
        if (!(value instanceof String text)) throw invalid();
        return text;
    }

    private static LocalDate fechaNacimiento(Object value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value.toString());
        } catch (DateTimeParseException | NullPointerException malformed) {
            throw invalid();
        }
    }

    private static OfflineOperationConflict invalid() {
        return new OfflineOperationConflict("INVALID_INPUT");
    }
}
