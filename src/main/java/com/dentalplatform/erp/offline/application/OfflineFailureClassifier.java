package com.dentalplatform.erp.offline.application;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.postgresql.util.PSQLException;

import java.sql.SQLException;

/**
 * Maps a failed synchronization attempt to the stable reason code the client reports.
 *
 * <p>A unique violation is classified in this order: first the driver's typed structured server error
 * (the PostgreSQL driver is a compile-scope dependency, so it is imported directly), then an identifier scan
 * over the cause messages as a last resort for a shaded, relocated or wrapped driver, and only then a
 * permanent INVALID_INPUT with a structured warning so the degradation is observable. Prose is never the
 * primary signal. The identifier scan is a last-resort heuristic: it can misread a duplicated VALUE
 * shaped like the constraint name, so it is never treated as authoritative, while a real duplicate
 * still classifies correctly on either path.</p>
 */
public final class OfflineFailureClassifier {
    private static final Logger log = LoggerFactory.getLogger(OfflineFailureClassifier.class);
    private static final String PATIENT_CI_NIT = "pacientes_ci_nit";

    private OfflineFailureClassifier() {
    }

    public static String reasonCode(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if ("23505".equals(state)) {
                    String constraint = duplicateConstraint(failure);
                    if (constraint == null) constraint = scanConstraintIdentifiers(failure);
                    if (constraint == null) {
                        // Degrade observably: a shaded driver or an opaque wrapper must not hide a real duplicate.
                        log.warn("Unique violation without a readable constraint name: sqlState={} exceptionClass={}",
                                state, sql.getClass().getName());
                        return "INVALID_INPUT";
                    }
                    return isPatientCiNit(constraint) ? "PATIENT_DUPLICATE" : "INVALID_INPUT";
                }
                if ("22001".equals(state) || "22021".equals(state) || "22P05".equals(state)
                        || "23502".equals(state) || "23514".equals(state)) return "INVALID_INPUT";
            }
            if (cause instanceof BusinessRuleException rule) {
                // The rule owns its stable code; an absent or unknown code stays a plain invalid input.
                if (BusinessRuleException.PATIENT_CI_NIT_DUPLICATE.equals(rule.code())) return "PATIENT_DUPLICATE";
                return "INVALID_INPUT";
            }
            if (cause instanceof IllegalArgumentException) return "INVALID_INPUT";
        }
        return "TECHNICAL_RETRY_REQUIRED";
    }

    /**
     * Looks for a PostgreSQL structured server error anywhere in the cause chain, not only on the exception that
     * carried the SQLState: wrappers and relocated drivers can hide it one level down.
     */
    static String duplicateConstraint(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (!(cause instanceof SQLException sql)) continue;
            String constraint = structuredConstraint(sql);
            if (constraint != null) return constraint;
        }
        return null;
    }

    private static String structuredConstraint(SQLException sql) {
        if (!(sql instanceof PSQLException postgres)) return null;
        var message = postgres.getServerErrorMessage();
        if (message == null) return null;
        String constraint = message.getConstraint();
        return constraint == null ? null : constraint;
    }

    /** Fallback for a driver the typed accessor cannot see: scan cause messages for the constraint identifier. */
    static String scanConstraintIdentifiers(Throwable failure) {
        var identifier = java.util.regex.Pattern.compile("[A-Za-z0-9_]+");
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) continue;
            var tokens = identifier.matcher(message);
            while (tokens.find()) {
                String token = tokens.group();
                if (isPatientCiNit(token)) return token;
            }
        }
        return null;
    }

    private static boolean isPatientCiNit(String constraint) {
        return constraint.equals(PATIENT_CI_NIT) || constraint.startsWith(PATIENT_CI_NIT + "_");
    }
}
