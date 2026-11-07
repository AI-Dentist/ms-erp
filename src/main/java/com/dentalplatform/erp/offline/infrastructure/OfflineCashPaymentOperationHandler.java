package com.dentalplatform.erp.offline.infrastructure;

import com.dentalplatform.erp.configsecurity.application.port.OfflineSyncIdentityPort;
import com.dentalplatform.erp.financebilling.application.command.CobrarEfectivoCommand;
import com.dentalplatform.erp.financebilling.application.usecase.CobrarCuotaEfectivoUseCase;
import com.dentalplatform.erp.offline.application.OfflineOperationHandler;
import com.dentalplatform.erp.offline.application.OfflineOperationStore;
import com.dentalplatform.erp.offline.domain.OfflineOperationConflict;
import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt;
import com.dentalplatform.erp.offline.domain.OfflineSyncFailure;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Validates the frozen expectations while holding the same order lock as online billing. */
@Component
public class OfflineCashPaymentOperationHandler implements OfflineOperationHandler {
    private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999.99");
    private final CobrarCuotaEfectivoUseCase cash;
    private final OfflineSyncIdentityPort identities;
    private final OfflineOperationStore operations;
    private final JdbcTemplate jdbc;
    @PersistenceContext private EntityManager entityManager;

    public OfflineCashPaymentOperationHandler(CobrarCuotaEfectivoUseCase cash, OfflineSyncIdentityPort identities,
                                               OfflineOperationStore operations, JdbcTemplate jdbc) {
        this.cash = cash;
        this.identities = identities;
        this.operations = operations;
        this.jdbc = jdbc;
    }

    @Override public OfflineOperationReceipt.Type type() { return OfflineOperationReceipt.Type.CASH_PAYMENT; }

    @Override
    public OfflineOperationReceipt.Result execute(Map<String, Object> payload, UUID actorId) {
        var caller = identities.current();
        if (!caller.actorId().equals(actorId)) throw new OfflineSyncFailure("ACTOR_LINK_CHANGED");
        if (Boolean.TRUE.equals(payload.get("requireOpenCashRegister"))) {
            throw new OfflineOperationConflict("CASH_REGISTER_STATE_NOT_MODELLED");
        }
        BigDecimal expectedBalance = money(payload.get("expectedOrderBalanceBs"));
        BigDecimal expectedAmount = money(payload.get("expectedQuotaAmountBs"));
        String ci = text(payload.get("ciNitCliente"), 30);
        String name = text(payload.get("razonSocialCliente"), 150);
        UUID quota = quotaReference(object(payload.get("quota")), caller);

        // This initial lookup is intentionally unlocked. Recheck the relationship after the
        // order lock: a concurrent plan replacement may remove the original installment.
        var orderIds = jdbc.query("SELECT orden_cobro_id FROM cuotas_pago WHERE id = ?",
                (rs, row) -> rs.getObject(1, UUID.class), quota);
        if (orderIds.size() != 1) throw conflict("STALE_QUOTA");
        UUID orderId = orderIds.getFirst();
        var orders = jdbc.query("SELECT estado, saldo_pendiente_bs FROM ordenes_cobro WHERE id = ? FOR UPDATE",
                (rs, row) -> new FinancialState(rs.getString(1), rs.getBigDecimal(2)), orderId);
        if (orders.size() != 1) throw conflict("STALE_ORDER");
        var quotas = jdbc.query("SELECT estado, monto_cuota_bs FROM cuotas_pago WHERE id = ? AND orden_cobro_id = ? FOR UPDATE",
                (rs, row) -> new FinancialState(rs.getString(1), rs.getBigDecimal(2)), quota, orderId);
        if (quotas.size() != 1) throw conflict("STALE_QUOTA");
        var order = orders.getFirst();
        var installment = quotas.getFirst();
        if (!order.state().equals(String.valueOf(payload.get("expectedOrderState")))
                || order.amount().compareTo(expectedBalance) != 0
                || !(order.state().equals("PENDIENTE") || order.state().equals("PARCIAL"))) {
            throw conflict("STALE_ORDER");
        }
        if (!installment.state().equals(String.valueOf(payload.get("expectedQuotaState")))
                || installment.amount().compareTo(expectedAmount) != 0
                || !installment.state().equals("PENDIENTE") || installment.amount().signum() <= 0
                || installment.amount().compareTo(order.amount()) > 0) {
            throw conflict("STALE_QUOTA");
        }
        var receipt = cash.ejecutar(new CobrarEfectivoCommand(quota, actorId, ci, name));
        entityManager.flush();
        return new OfflineOperationReceipt.Result(null, orderId, List.of(), receipt.movimientoCajaId(), receipt.id());
    }

    private record FinancialState(String state, BigDecimal amount) { }

    private UUID quotaReference(Map<String, Object> reference, OfflineSyncIdentityPort.Caller caller) {
        Object id = reference.get("id");
        Object operationId = reference.get("operationId");
        Object number = reference.get("installmentNumber");
        if ((id == null) == (operationId == null)) throw invalid();
        if (id != null) {
            if (number != null) throw invalid();
            return uuid(id);
        }
        if (!(number instanceof Number n) || n.intValue() <= 0 || n.doubleValue() != n.intValue()) throw invalid();
        UUID parentId = uuid(operationId);
        var header = operations.lockHeader(caller.actorId(), parentId).orElseThrow(() -> blocked("DEPENDENCY_PENDING"));
        if (!header.issuer().equals(caller.issuer()) || !header.subject().equals(caller.subject())) {
            throw new OfflineSyncFailure("ACTOR_LINK_CHANGED");
        }
        if (header.type() != OfflineOperationReceipt.Type.MANUAL_ORDER) throw invalid();
        var parent = operations.receipt(caller.actorId(), parentId).orElseThrow();
        if (parent.state() == OfflineOperationReceipt.State.CONFLICT) throw blocked("DEPENDENCY_CONFLICT");
        if (parent.state() != OfflineOperationReceipt.State.CONFIRMED) throw blocked("DEPENDENCY_PENDING");
        if (parent.result() == null || parent.result().orderId() == null) throw new IllegalStateException("Missing confirmed order mapping");
        return parent.result().installments().stream().filter(item -> item.number() == n.intValue())
                .map(OfflineOperationReceipt.Installment::id).findFirst().orElseThrow(OfflineCashPaymentOperationHandler::invalid);
    }

    private static BigDecimal money(Object value) {
        if (value == null) throw invalid();
        BigDecimal amount;
        try { amount = new BigDecimal(value.toString()); }
        catch (NumberFormatException rejected) { throw invalid(); }
        if (amount.signum() < 0 || amount.stripTrailingZeros().scale() > 2 || amount.compareTo(MAX_MONEY) > 0) throw invalid();
        return amount;
    }

    private static String text(Object value, int max) {
        if (!(value instanceof String text) || text.isBlank() || text.trim().length() > max) throw invalid();
        return text;
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

    private static OfflineOperationConflict invalid() { return conflict("INVALID_INPUT"); }
    private static OfflineOperationConflict conflict(String reason) { return new OfflineOperationConflict(reason); }
    private static OfflineOperationConflict blocked(String reason) {
        return new OfflineOperationConflict(reason, OfflineOperationReceipt.State.BLOCKED);
    }
}
