package com.dentalplatform.erp.offline.application;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;

import java.lang.reflect.Method;
import java.sql.SQLException;

/**
 * Maps a failed synchronization attempt to the stable reason code the client reports.
 *
 * <p>A unique violation is classified by the PostgreSQL constraint identifier carried in the driver's
 * structured server error, never by scraping prose: the server may answer in Spanish with mangled quoting,
 * and a duplicated VALUE could contain the constraint token. The driver is a runtime dependency, so the
 * accessor is read reflectively instead of importing the driver into the application layer.</p>
 */
public final class OfflineFailureClassifier {
    private static final String PATIENT_CI_NIT = "pacientes_ci_nit";

    private OfflineFailureClassifier() {
    }

    public static String reasonCode(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if ("23505".equals(state)) {
                    String constraint = duplicateConstraint(sql);
                    return constraint != null && isPatientCiNit(constraint) ? "PATIENT_DUPLICATE" : "INVALID_INPUT";
                }
                if ("22001".equals(state) || "22021".equals(state) || "22P05".equals(state)
                        || "23502".equals(state) || "23514".equals(state)) return "INVALID_INPUT";
            }
            if (cause instanceof BusinessRuleException || cause instanceof IllegalArgumentException) return "INVALID_INPUT";
        }
        return "TECHNICAL_RETRY_REQUIRED";
    }

    /** Reads the violated constraint name from the driver's structured server error when it is available. */
    static String duplicateConstraint(SQLException sql) {
        try {
            Method serverError = sql.getClass().getMethod("getServerErrorMessage");
            Object message = serverError.invoke(sql);
            if (message == null) return null;
            Object constraint = message.getClass().getMethod("getConstraint").invoke(message);
            return constraint == null ? null : constraint.toString();
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    private static boolean isPatientCiNit(String constraint) {
        return constraint.equals(PATIENT_CI_NIT) || constraint.startsWith(PATIENT_CI_NIT + "_");
    }
}
