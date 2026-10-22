package com.dentalplatform.erp.shared.infrastructure.idempotency;

import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
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
