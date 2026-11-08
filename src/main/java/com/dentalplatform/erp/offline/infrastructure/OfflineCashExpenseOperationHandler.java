package com.dentalplatform.erp.offline.infrastructure;

import com.dentalplatform.erp.configsecurity.application.port.OfflineSyncIdentityPort;
import com.dentalplatform.erp.financebilling.application.command.RegistrarEgresoCommand;
import com.dentalplatform.erp.financebilling.application.usecase.RegistrarEgresoCajaUseCase;
import com.dentalplatform.erp.financebilling.domain.model.MetodoPago;
import com.dentalplatform.erp.financebilling.domain.model.MovimientoCaja;
import com.dentalplatform.erp.financebilling.domain.model.TipoMovimientoCaja;
import com.dentalplatform.erp.offline.application.OfflineOperationHandler;
import com.dentalplatform.erp.offline.application.OfflineSyncSessionStore;
import com.dentalplatform.erp.offline.domain.OfflineOperationConflict;
import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt;
import com.dentalplatform.erp.offline.domain.OfflineSyncFailure;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.vo.Money;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Serializes all movement inserts while comparing the frozen accounting balance. */
@Component
public class OfflineCashExpenseOperationHandler implements OfflineOperationHandler {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");
    private final RegistrarEgresoCajaUseCase expenses;
    private final OfflineSyncIdentityPort identities;
    private final OfflineSyncSessionStore sessions;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    @PersistenceContext private EntityManager entityManager;

    public OfflineCashExpenseOperationHandler(RegistrarEgresoCajaUseCase expenses, OfflineSyncIdentityPort identities,
                                              OfflineSyncSessionStore sessions, JdbcTemplate jdbc, Clock clock) {
        this.expenses = expenses;
        this.identities = identities;
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override public OfflineOperationReceipt.Type type() { return OfflineOperationReceipt.Type.CASH_EXPENSE; }

    @Override
    public OfflineOperationReceipt.Result execute(Map<String, Object> payload, UUID actorId) {
        if (!identities.current().actorId().equals(actorId)) throw new OfflineSyncFailure("ACTOR_LINK_CHANGED");
        if (Boolean.TRUE.equals(payload.get("requireOpenCashRegister"))) {
            throw new OfflineOperationConflict("CASH_REGISTER_STATE_NOT_MODELLED");
        }
        BigDecimal amount = decimal(payload.get("montoBs"));
        BigDecimal expected = decimal(payload.get("expectedCashflowBalanceBs"));
        if (amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0) throw invalid();
        String category;
        String description;
        MetodoPago method;
        try {
            category = (String) payload.get("categoria");
            description = (String) payload.get("descripcion");
            method = MetodoPago.valueOf(payload.get("metodoPago").toString());
            // Reuse the existing domain validation before acquiring the ledger lock or writing.
            MovimientoCaja.crear(null, actorId, TipoMovimientoCaja.EGRESO, category, method,
                    Money.of(amount), description, OffsetDateTime.now(clock));
        } catch (BusinessRuleException | IllegalArgumentException | ClassCastException | NullPointerException rejected) {
            throw invalid();
        }
        jdbc.execute("LOCK TABLE movimientos_caja IN SHARE ROW EXCLUSIVE MODE");
        if (sessions.cashflowBalance().compareTo(expected) != 0) {
            throw new OfflineOperationConflict("STALE_CASHFLOW_BALANCE");
        }
        var movement = expenses.ejecutar(new RegistrarEgresoCommand(actorId, category, method, amount, description));
        entityManager.flush();
        return new OfflineOperationReceipt.Result(null, null, List.of(), movement.id(), null);
    }

    private static BigDecimal decimal(Object value) {
        try {
            BigDecimal number = new BigDecimal(value.toString());
            if (number.stripTrailingZeros().scale() > 2) throw invalid();
            return number;
        } catch (NumberFormatException | NullPointerException rejected) {
            throw invalid();
        }
    }

    private static OfflineOperationConflict invalid() { return new OfflineOperationConflict("INVALID_INPUT"); }
}
