package com.dentalplatform.erp.financebilling.report;

import com.dentalplatform.erp.financebilling.domain.report.SqlReadOnlyGuardrail;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class SqlReadOnlyGuardrailTest {
    private final SqlReadOnlyGuardrail guard = new SqlReadOnlyGuardrail();

    /** SQL that already carries an explicit LIMIT: returned unchanged (trimmed). */
    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT status, SUM(total_bs), AVG(balance_bs), MIN(total_bs), MAX(total_bs), COUNT(*) FROM report_orders GROUP BY status ORDER BY status DESC LIMIT 500",
        "SELECT code, price_bs FROM report_services WHERE price_bs >= 0 AND active = true ORDER BY price_bs ASC LIMIT 1",
        "SELECT COUNT(*) FROM report_orders LIMIT 1"
    })
    void acceptsOnlyDocumentedGrammarWithExplicitLimit(String sql) {
        assertThat(guard.validate(sql)).isEqualTo(sql.trim());
    }

    /** SQL without LIMIT: validate() must append LIMIT 500. */
    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT * FROM report_income",
        "select day, category, payment_method, amount_bs, movement_count from report_income",
        "SELECT * FROM report_income WHERE day >= '2026-01-01' AND category = 'COBRO_CUOTA'",
        "SELECT * FROM report_income WHERE category = 'SETTLEMENT'",
        "SELECT * FROM report_income WHERE category = 'UPDATED'"
    })
    void appendsLimit500WhenAbsent(String sql) {
        assertThat(guard.validate(sql)).isEqualTo(sql.trim() + " LIMIT 500");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "SELECT * FROM pacientes", "SELECT * FROM usuarios", "SELECT * FROM pg_catalog.pg_roles",
        "SELECT * FROM public.report_income", "SELECT pg_sleep(10) FROM report_income",
        "SELECT nextval('recibo_numero_seq') FROM report_income", "SELECT dblink('x','x') FROM report_income",
        "SELECT lo_import('/etc/passwd') FROM report_income", "SELECT * INTO x FROM report_income",
        "SELECT * FROM report_income FOR UPDATE", "SELECT * FROM report_income; DELETE FROM pacientes",
        "SELECT * FROM report_income;", "SELECT /*x*/ * FROM report_income", "SELECT * FROM report_income --x",
        "WITH x AS (DELETE FROM pacientes RETURNING *) SELECT * FROM x",
        "SELECT * FROM report_income UNION SELECT * FROM pacientes",
        "SELECT (SELECT COUNT(*) FROM pacientes) FROM report_income", "SELECT * FROM report_income, pacientes",
        "SELECT * FROM report_income JOIN pacientes ON true", "SELECT secret FROM report_income",
        "SELECT amount_bs::text FROM report_income", "SELECT \"amount_bs\" FROM report_income",
        "SELECT sum(pg_sleep(1)) FROM report_income", "SELECT COUNT(*) AS n FROM report_income",
        "SELECT * FROM report_services LIMIT 0", "SELECT * FROM report_services LIMIT 501",
        "SELECT * FROM report_services LIMIT 1 OFFSET 2", "SELECT * FROM report_services WHERE active = true OR true",
        "SELECT * FROM report_income WHERE category = 'DROP'", "SELECT * FROM report_income WHERE category = 'x''y'",
        "SELECT * FROM report_income WHERE amount_bs = NaN", "SELECT * FROM report_income WHERE day = current_date",
        "SELECT * FROM report_orders WHERE status = 'x'; ALTER TABLE pacientes ADD x text",
        "SELECT * FROM report_orders WHERE status = 'x' AND EXISTS (SELECT 1)",
        "SELECT * FROM report_services WHERE price_bs > 1e100", "SELECT * FROM report_income\u0000"
    })
    void rejectsUncertainSyntaxWithNoSqlInError(String sql) {
        assertThatThrownBy(() -> guard.validate(sql)).isInstanceOf(BusinessRuleException.class)
                .hasMessage("Consulta de reporte no permitida");
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE", "CREATE", "GRANT",
        "REVOKE", "MERGE", "COPY", "CALL", "DO", "EXECUTE", "EXEC", "SET", "LOCK", "VACUUM", "INTO",
        "pg_sleep", "pg_catalog", "dblink", "lo_import", "information_schema"})
    void rejectsForbiddenTokensEvenInsideStringLiterals(String token) {
        for (String variant : new String[]{token, token.toLowerCase(java.util.Locale.ROOT), token.toUpperCase(java.util.Locale.ROOT)}) {
            assertThatThrownBy(() -> guard.validate("SELECT * FROM report_income WHERE category = '" + variant + "'"))
                    .isInstanceOf(BusinessRuleException.class).hasMessage("Consulta de reporte no permitida");
            assertThatThrownBy(() -> guard.validate("SELECT \"" + variant + "\" FROM report_income"))
                    .isInstanceOf(BusinessRuleException.class).hasMessage("Consulta de reporte no permitida");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT * FROM report_income # comment", "SELECT * FROM report_income */",
        "SELECT * FROM report_income FOR SHARE", "SELECT * FROM information_schema.tables",
        "SELECT * FROM report_income WHERE category = 'a; b'", "SELECT * FROM report_income WHERE category = '--'",
        "SELECT * FROM report_income WHERE category = '/*x*/'", "SELECT * FROM report_income WHERE category = '#'"})
    void rejectsEveryCommentAndStatementDelimiter(String sql) {
        assertThatThrownBy(() -> guard.validate(sql)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void boundsInputAndRejectsNull() {
        assertThatThrownBy(() -> guard.validate(null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> guard.validate("SELECT " + "a".repeat(4001))).isInstanceOf(BusinessRuleException.class);
    }
}
