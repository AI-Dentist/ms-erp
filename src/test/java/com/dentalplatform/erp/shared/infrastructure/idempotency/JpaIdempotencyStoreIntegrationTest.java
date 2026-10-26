package com.dentalplatform.erp.shared.infrastructure.idempotency;

import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static com.dentalplatform.erp.shared.infrastructure.idempotency.IdempotencyStore.ClaimStatus.ACQUIRED;
import static com.dentalplatform.erp.shared.infrastructure.idempotency.IdempotencyStore.ClaimStatus.COMPLETED;
import static com.dentalplatform.erp.shared.infrastructure.idempotency.IdempotencyStore.ClaimStatus.IN_PROGRESS;
import static com.dentalplatform.erp.shared.infrastructure.idempotency.IdempotencyStore.ClaimStatus.OPERATION_MISMATCH;
import static org.assertj.core.api.Assertions.assertThat;

class JpaIdempotencyStoreIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JpaIdempotencyStore store;

    @Autowired
    private IdempotencyRecordJpaRepository repository;
    @Autowired
    private JsonMapper mapper;
    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void expiryAndPurgeReclaimOnlyCompletedKeysWithInjectedClock() {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        var early = new JpaIdempotencyStore(repository, mapper,
                Clock.fixed(now, ZoneOffset.UTC));
        var late = new JpaIdempotencyStore(repository, mapper,
                Clock.fixed(now.plusSeconds(61), ZoneOffset.UTC));
        var tx = new TransactionTemplate(transactions);
        String pending = "pending-" + UUID.randomUUID();
        String completed = "completed-" + UUID.randomUUID();
        String purged = "purged-" + UUID.randomUUID();
        var response = new IdempotencyStore.StoredResponse(200, "application/json", "{\"ok\":true}");
        for (String key : new String[]{pending, completed, purged}) {
            assertThat(tx.execute(s -> early.claim(key, "POST /graphql", now.plusSeconds(60))).status())
                    .isEqualTo(ACQUIRED);
        }
        tx.executeWithoutResult(s -> early.complete(completed, response));
        tx.executeWithoutResult(s -> early.complete(purged, response));
        assertThat(tx.execute(s -> early.claim(completed, "POST /graphql", now.plusSeconds(60))).response())
                .isEqualTo(response);
        assertThat(tx.execute(s -> late.claim(pending, "POST /graphql", now.plusSeconds(120))).status())
                .isEqualTo(IN_PROGRESS);
        assertThat(tx.execute(s -> late.claim(completed, "POST /graphql", now.plusSeconds(120))).status())
                .isEqualTo(ACQUIRED);
        tx.executeWithoutResult(s -> late.purgeExpired());
        assertThat(tx.execute(s -> late.claim(pending, "POST /graphql", now.plusSeconds(120))).status())
                .isEqualTo(IN_PROGRESS);
        assertThat(tx.execute(s -> late.claim(purged, "POST /graphql", now.plusSeconds(120))).status())
                .isEqualTo(ACQUIRED);
    }

    private final Instant expires = Instant.now().plus(1, ChronoUnit.HOURS);

    private int rows(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?", Integer.class, key);
    }

    @Test
    void claimLifecycleAcquiresThenReportsInProgressThenCompleted() {
        String key = "it-" + UUID.randomUUID();
        assertThat(store.claim(key, "POST /graphql", expires).status()).isEqualTo(ACQUIRED);
        assertThat(store.claim(key, "POST /graphql", expires).status()).isEqualTo(IN_PROGRESS);

        var response = new IdempotencyStore.StoredResponse(200, "application/json", "{\"ok\":true}");
        store.complete(key, response);

        var replay = store.claim(key, "POST /graphql", expires);
        assertThat(replay.status()).isEqualTo(COMPLETED);
        assertThat(replay.response()).isEqualTo(response);
        assertThat(rows(key)).isEqualTo(1);
    }

    @Test
    void differentOperationWithSameKeyIsMismatch() {
        String key = "it-" + UUID.randomUUID();
        store.claim(key, "POST /graphql", expires);
        assertThat(store.claim(key, "POST /api/v1/other", expires).status()).isEqualTo(OPERATION_MISMATCH);
        assertThat(rows(key)).isEqualTo(1);
    }

    @Test
    void releaseRemovesTheRowAndAllowsReclaim() {
        String key = "it-" + UUID.randomUUID();
        store.claim(key, "POST /graphql", expires);
        store.release(key);
        assertThat(rows(key)).isZero();
        assertThat(store.claim(key, "POST /graphql", expires).status()).isEqualTo(ACQUIRED);
    }
}
