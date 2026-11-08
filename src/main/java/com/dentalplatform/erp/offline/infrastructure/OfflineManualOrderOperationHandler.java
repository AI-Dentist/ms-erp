package com.dentalplatform.erp.offline.infrastructure;

import com.dentalplatform.erp.configsecurity.application.port.OfflineSyncIdentityPort;
import com.dentalplatform.erp.financebilling.application.command.CrearOrdenCobroCommand;
import com.dentalplatform.erp.financebilling.application.usecase.CrearOrdenCobroManualUseCase;
import com.dentalplatform.erp.offline.application.OfflineOperationHandler;
import com.dentalplatform.erp.offline.application.OfflineOperationStore;
import com.dentalplatform.erp.offline.domain.OfflineOperationConflict;
import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt;
import com.dentalplatform.erp.offline.domain.OfflineSyncFailure;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pins active references and tariffs against the unchanged online JPA UPDATE paths. */
@Component
public class OfflineManualOrderOperationHandler implements OfflineOperationHandler {
    private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999.99");
    private final CrearOrdenCobroManualUseCase orders;
    private final OfflineSyncIdentityPort identities;
    private final OfflineOperationStore operations;
    private final JdbcTemplate jdbc;
    @PersistenceContext private EntityManager entityManager;

    public OfflineManualOrderOperationHandler(CrearOrdenCobroManualUseCase orders, OfflineSyncIdentityPort identities,
                                              OfflineOperationStore operations, JdbcTemplate jdbc) {
        this.orders = orders;
        this.identities = identities;
        this.operations = operations;
        this.jdbc = jdbc;
    }

    @Override public OfflineOperationReceipt.Type type() { return OfflineOperationReceipt.Type.MANUAL_ORDER; }

    @Override
    public OfflineOperationReceipt.Result execute(Map<String, Object> payload, UUID actorId) {
        try {
            var caller = identities.current();
            if (!caller.actorId().equals(actorId)) throw new OfflineSyncFailure("ACTOR_LINK_CHANGED");
            UUID patient = patientReference(object(payload.get("patient")), caller);
            lockActive("pacientes", patient);
            UUID doctor = payload.get("odontologoId") == null ? null : uuid(payload.get("odontologoId"));
            if (doctor != null) lockActive("odontologos", doctor);
            if (!(payload.get("items") instanceof List<?> items) || items.isEmpty()) throw invalid();
            var commands = new ArrayList<CrearOrdenCobroCommand.Item>();
            var expected = new ArrayList<BigDecimal>();
            for (Object value : items) {
                var item = object(value);
                if (!(item.get("cantidad") instanceof Number quantity) || quantity.intValue() < 1) throw invalid();
                commands.add(new CrearOrdenCobroCommand.Item(uuid(item.get("servicioId")),
                        (String) item.get("piezaDental"), quantity.intValue()));
                expected.add(money(item.get("expectedUnitPriceBs")));
            }
            // A single ordering avoids opposing service-lock orders in multi-item requests.
            var prices = new HashMap<UUID, BigDecimal>();
            commands.stream().map(CrearOrdenCobroCommand.Item::servicioId).distinct()
                    .sorted(Comparator.comparing(UUID::toString)).forEach(id -> prices.put(id, lockPrice(id)));
            BigDecimal subtotal = BigDecimal.ZERO;
            for (int i = 0; i < commands.size(); i++) {
                var item = commands.get(i);
                BigDecimal current = prices.get(item.servicioId());
                if (expected.get(i).compareTo(current) != 0) throw new OfflineOperationConflict("STALE_PRICE");
                subtotal = subtotal.add(current.multiply(BigDecimal.valueOf(item.cantidad())));
            }
            money(subtotal);
            BigDecimal discount = payload.get("descuentoBs") == null ? BigDecimal.ZERO : money(payload.get("descuentoBs"));
            if (discount.compareTo(subtotal) > 0) throw invalid();
            if (money(payload.get("expectedTotalBs")).compareTo(subtotal.subtract(discount)) != 0) {
                throw new OfflineOperationConflict("STALE_PRICE");
            }
            var order = orders.ejecutar(new CrearOrdenCobroCommand(patient, doctor, commands, discount,
                    (String) payload.get("observaciones")));
            entityManager.flush();
            var installments = order.getCuotas().stream().sorted(Comparator.comparingInt(c -> c.numeroCuota()))
                    .map(c -> new OfflineOperationReceipt.Installment(c.numeroCuota(), c.id())).toList();
            return new OfflineOperationReceipt.Result(null, order.getId(), installments, null, null);
        } catch (BusinessRuleException rejected) {
            throw invalid();
        }
    }

    private UUID patientReference(Map<String, Object> reference, OfflineSyncIdentityPort.Caller caller) {
        Object id = reference.get("id");
        Object operationId = reference.get("operationId");
        if ((id == null) == (operationId == null) || reference.get("installmentNumber") != null) throw invalid();
        if (id != null) return uuid(id);
        UUID parentId = uuid(operationId);
        var header = operations.lockHeader(caller.actorId(), parentId).orElseThrow(() -> blocked("DEPENDENCY_PENDING"));
        if (!header.issuer().equals(caller.issuer()) || !header.subject().equals(caller.subject())) {
            throw new OfflineSyncFailure("ACTOR_LINK_CHANGED");
        }
        if (header.type() != OfflineOperationReceipt.Type.PATIENT_CREATE) throw invalid();
        // Internal mapping resolution authorizes the child, not a replay of a parent's complete receipt.
        var parent = operations.receipt(caller.actorId(), parentId).orElseThrow();
        if (parent.state() == OfflineOperationReceipt.State.CONFLICT) throw blocked("DEPENDENCY_CONFLICT");
        if (parent.state() != OfflineOperationReceipt.State.CONFIRMED) throw blocked("DEPENDENCY_PENDING");
        if (parent.result() == null || parent.result().patientId() == null) throw new IllegalStateException("Missing confirmed patient mapping");
        return parent.result().patientId();
    }

    private void lockActive(String table, UUID id) {
        // Table names are private constants supplied only by execute, never client SQL.
        var active = jdbc.query("SELECT activo FROM " + table + " WHERE id = ? FOR SHARE", (rs, row) -> rs.getBoolean(1), id);
        if (active.size() != 1 || !active.getFirst()) throw new OfflineOperationConflict("INACTIVE_REFERENCE");
    }

    private BigDecimal lockPrice(UUID id) {
        var prices = jdbc.query("SELECT activo, precio_base_bs FROM servicios_odontologicos WHERE id = ? FOR SHARE",
                (rs, row) -> {
                    if (!rs.getBoolean("activo")) throw new OfflineOperationConflict("INACTIVE_REFERENCE");
                    return rs.getBigDecimal("precio_base_bs");
                }, id);
        if (prices.size() != 1) throw new OfflineOperationConflict("INACTIVE_REFERENCE");
        return money(prices.getFirst());
    }

    private static BigDecimal money(Object value) {
        if (value == null) throw invalid();
        BigDecimal amount;
        try { amount = new BigDecimal(value.toString()); }
        catch (NumberFormatException rejected) { throw invalid(); }
        // Validate BEFORE Money.of's HALF_UP rounding and before NUMERIC(12,2) persistence.
        if (amount.signum() < 0 || amount.stripTrailingZeros().scale() > 2 || amount.compareTo(MAX_MONEY) > 0) throw invalid();
        return amount;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw invalid();
        return (Map<String, Object>) value;
    }

    private static UUID uuid(Object value) {
        try { return UUID.fromString(value.toString()); }
        catch (IllegalArgumentException | NullPointerException rejected) { throw invalid(); }
    }

    private static OfflineOperationConflict invalid() { return new OfflineOperationConflict("INVALID_INPUT"); }
    private static OfflineOperationConflict blocked(String reason) {
        return new OfflineOperationConflict(reason, OfflineOperationReceipt.State.BLOCKED);
    }
}
