package com.dentalplatform.erp.offline.infrastructure;

import com.dentalplatform.erp.mastercatalog.application.command.CrearPacienteCommand;
import com.dentalplatform.erp.mastercatalog.application.usecase.CrearPacienteUseCase;
import com.dentalplatform.erp.offline.application.OfflineOperationHandler;
import com.dentalplatform.erp.offline.domain.OfflineOperationConflict;
import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class OfflinePatientOperationHandler implements OfflineOperationHandler {
    private final CrearPacienteUseCase patients;
    private final JdbcTemplate jdbc;
    @PersistenceContext private EntityManager entityManager;

    public OfflinePatientOperationHandler(CrearPacienteUseCase patients, JdbcTemplate jdbc) {
        this.patients = patients;
        this.jdbc = jdbc;
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
            // Classify the duplicate rule from data, never from the legacy localized message: the existing use
            // case rejects a repeated CI/NIT before any insert, and the unique index stays the fallback for a race.
            if (!jdbc.query("SELECT 1 FROM pacientes WHERE ci_nit = ?", (rs, row) -> 1, ciNit).isEmpty()) {
                throw new OfflineOperationConflict("PATIENT_DUPLICATE");
            }
            var patient = patients.ejecutar(new CrearPacienteCommand(ciNit, nombres, apellidos, fechaNacimiento,
                    genero, telefono, email, direccion));
            entityManager.flush();
            return new OfflineOperationReceipt.Result(patient.getId(), null, List.of(), null, null);
        } catch (BusinessRuleException rejected) {
            // Translate the existing duplicate rule, not GraphQL error text or a success heuristic.
            if (rejected.getMessage() != null
                    && rejected.getMessage().startsWith("Ya existe un paciente con el CI/NIT ")) {
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
