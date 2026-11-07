package com.dentalplatform.erp.offline;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import java.sql.SQLException;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIX 3 contract: a failed operation is classified from PostgreSQL's <em>structured</em> server error — the
 * SQLState plus the violated constraint identifier — never by scraping the localized prose of the message.
 *
 * <p>Target under contract: a public static helper in the offline application package,
 * {@code com.dentalplatform.erp.offline.application.OfflineFailureClassifier.reasonCode(Throwable)}, returning a
 * stable reason code ({@code PATIENT_DUPLICATE}, {@code INVALID_INPUT}, {@code TECHNICAL_RETRY_REQUIRED}). The
 * production code cannot import the driver (it is {@code runtime} scope); these tests can, which is exactly why
 * the fixtures below are real {@link PSQLException} instances built from PostgreSQL's wire-format error response.
 *
 * <p>Each test name states the contract it locks; the comment above each assertion names the regression the
 * production fix has to prevent.
 */
class OfflineFailureClassifierTest {
    private static final String CI_NIT_CONSTRAINT = "pacientes_ci_nit_key";

    /**
     * Compile-time bridge for this test-first checkpoint: the production helper does not exist yet, so the
     * contract is invoked reflectively and a missing helper is a plain assertion failure (RED), not a broken
     * test compile. When {@code OfflineFailureClassifier} lands, replace this method body with
     * {@code return OfflineFailureClassifier.reasonCode(failure);} — the contract name stays identical.
     */
    private static String reasonCode(Throwable failure) {
        return com.dentalplatform.erp.offline.application.OfflineFailureClassifier.reasonCode(failure);
    }


    /**
     * Builds the driver exception from PostgreSQL's wire-format ErrorResponse fields, exactly as the server
     * sends them: severity, SQLState, message, detail, table and the violated constraint identifier.
     */
    private static PSQLException uniqueViolation(String constraint, String message, String detail) {
        String wire = "SERROR\0VFATAL\0C23505\0M" + message + "\0D" + detail
                + "\0tpacientes\0n" + constraint + "\0\0";
        return new PSQLException(new ServerErrorMessage(wire));
    }

    @Test
    void duplicateCiNitConstraintIsPatientDuplicateEvenWhenTheDetailValueEchoesTheTokenText() {
        // Locks: a 23505 that really violates pacientes_ci_nit_key stays a permanent business duplicate even
        // when the duplicated value itself contains the token text.
        var duplicate = uniqueViolation(CI_NIT_CONSTRAINT,
                "duplicate key value violates unique constraint \"" + CI_NIT_CONSTRAINT + "\"",
                "Key (ci_nit)=(SYN-pacientes_ci_nit-1) already exists.");
        assertThat(duplicate.getSQLState()).isEqualTo("23505");
        assertThat(duplicate.getServerErrorMessage().getConstraint()).isEqualTo(CI_NIT_CONSTRAINT);
        assertThat(reasonCode(duplicate)).isEqualTo("PATIENT_DUPLICATE");
    }

    @Test
    void unrelatedUniqueViolationIsNotAPatientDuplicateOnlyBecauseItsValueEchoesTheToken() {
        // Locks: another constraint whose duplicated VALUE happens to contain "pacientes_ci_nit" must never be
        // reported as PATIENT_DUPLICATE; an unrelated unique violation is a durable non-retryable conflict.
        var unrelated = uniqueViolation("offline_operation_receipts_pkey",
                "duplicate key value violates unique constraint \"offline_operation_receipts_pkey\"",
                "Key (payload_hash)=(pacientes_ci_nit-42) already exists.");
        assertThat(unrelated.getSQLState()).isEqualTo("23505");
        assertThat(unrelated.getServerErrorMessage().getConstraint())
                .isNotEqualTo(CI_NIT_CONSTRAINT).isEqualTo("offline_operation_receipts_pkey");
        assertThat(unrelated.getMessage()).contains("pacientes_ci_nit");
        assertThat(reasonCode(unrelated)).isEqualTo("INVALID_INPUT");
    }

    @Test
    void theStructuredServerErrorIsFoundAnywhereInTheCauseChainNotOnlyAtTheTop() {
        // Locks: an intermediary SQLException that carries only SQLState 23505 and no server error must not hide
        // the real PSQLException deeper in the chain. Today the outer wrapper decides and a genuine CI/NIT
        // duplicate degrades from PATIENT_DUPLICATE to INVALID_INPUT, so the operator loses the duplicate
        // classification that the pending item has to show.
        // The replacement contract keeps this test unchanged: it asserts through the same public entry point,
        // reasonCode(Throwable); the port-based inspector behind it must scan the whole cause chain for a
        // structured server error, not only the outermost SQLException.
        var structured = uniqueViolation(CI_NIT_CONSTRAINT,
                "duplicate key value violates unique constraint \"" + CI_NIT_CONSTRAINT + "\"",
                "Key (ci_nit)=(SYN-pacientes_ci_nit-2) already exists.");
        var outer = new SQLException("duplicate key value violates unique constraint", "23505", structured);
        // Premise: the outermost exception has no driver server error, only the nested PSQLException does.
        assertThat(outer.getSQLState()).isEqualTo("23505");
        assertThat(outer.getCause()).isSameAs(structured);
        assertThat(((PSQLException) outer.getCause()).getServerErrorMessage().getConstraint())
                .isEqualTo(CI_NIT_CONSTRAINT);
        assertThat(reasonCode(outer)).isEqualTo("PATIENT_DUPLICATE");
    }

    @Test
    void renamedCiNitConstraintIsADurableInvalidInputNeverARetryableTechnicalFailure() {
        // Locks: renaming the unique constraint must not silently downgrade a permanent duplicate into
        // TECHNICAL_RETRY_REQUIRED, which would leave the client retrying the same pending item forever.
        var renamed = uniqueViolation("pacientes_ci_sin_prefijo_igual",
                "duplicate key value violates unique constraint \"pacientes_ci_sin_prefijo_igual\"",
                "Key (ci_nit)=(SYN-1) already exists.");
        assertThat(renamed.getSQLState()).isEqualTo("23505");
        assertThat(renamed.getServerErrorMessage().getConstraint()).isEqualTo("pacientes_ci_sin_prefijo_igual");
        assertThat(reasonCode(renamed)).isNotEqualTo("TECHNICAL_RETRY_REQUIRED").isEqualTo("INVALID_INPUT");
    }
    @Test
    void degradedExtractionIsObservableThroughAStructuredWarning() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(com.dentalplatform.erp.offline.application.OfflineFailureClassifier.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            // A 23505 whose exception exposes no readable constraint must not degrade silently.
            var opaque = new java.sql.SQLException("duplicate key value violates unique constraint", "23505");
            assertThat(com.dentalplatform.erp.offline.application.OfflineFailureClassifier.reasonCode(opaque))
                    .isEqualTo("INVALID_INPUT");
            assertThat(appender.list).hasSize(1);
            var event = appender.list.get(0);
            assertThat(event.getLevel().toString()).isEqualTo("WARN");
            assertThat(event.getArgumentArray()).containsExactly("23505", "java.sql.SQLException");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void aCodedDuplicateRuleIsPatientDuplicateAndAnUncodedOneStaysInvalidInput() {
        var coded = new com.dentalplatform.erp.shared.domain.exception.BusinessRuleException(
                "texto irrelevante", com.dentalplatform.erp.shared.domain.exception.BusinessRuleException.PATIENT_CI_NIT_DUPLICATE);
        assertThat(com.dentalplatform.erp.offline.application.OfflineFailureClassifier.reasonCode(coded))
                .isEqualTo("PATIENT_DUPLICATE");
        var uncoded = new com.dentalplatform.erp.shared.domain.exception.BusinessRuleException("solo texto");
        assertThat(com.dentalplatform.erp.offline.application.OfflineFailureClassifier.reasonCode(uncoded))
                .isEqualTo("INVALID_INPUT");
    }

    @Test
    void aNonDriverUniqueViolationStillFallsBackToTheIdentifierScan() {
        // Shaded or wrapped driver: the typed accessor cannot help, but the constraint token is still present.
        var opaque = new java.sql.SQLException(
                "llave duplicada viola restriccion de unicidad \"pacientes_ci_nit_key\"", "23505");
        assertThat(com.dentalplatform.erp.offline.application.OfflineFailureClassifier.reasonCode(opaque))
                .isEqualTo("PATIENT_DUPLICATE");
    }

    @Test
    void theIdentifierFallbackHeuristicCanMisreadAValueShapedLikeTheConstraint() {
        // Last-resort path only: with no typed server error the scan sees the VALUE text and reports a duplicate.
        // Pinned deliberately so the limitation is tested behaviour, never an unstated assumption.
        var shapedValue = new java.sql.SQLException(
                "duplicate key: detalle (ci_nit)=(pacientes_ci_nit)", "23505");
        assertThat(com.dentalplatform.erp.offline.application.OfflineFailureClassifier.reasonCode(shapedValue))
                .isEqualTo("PATIENT_DUPLICATE");
    }

}
