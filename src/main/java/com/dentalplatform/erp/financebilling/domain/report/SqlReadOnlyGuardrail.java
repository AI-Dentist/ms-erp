package com.dentalplatform.erp.financebilling.domain.report;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AST-like whitelist implemented as a tokenized closed grammar (strict regex), NOT a general SQL parser.
 * Each projection, aggregate, predicate, grouping and sort token must belong to the approved view schema.
 * No joins, subqueries, aliases, escaping, comments, SQL rewriting or arbitrary expressions are accepted.
 * The global deny scan also examines string literals; a forbidden token cannot be hidden in quoted data.
 * SCHEMA is the trusted, immutable allowlist-as-data extension point: extend only alongside reviewed views,
 * reporting-login grants and the effective-permission checks in ReportingJdbcQueryAdapter. Never accept it
 * from model/user input. This is defense in depth; the independent SELECT-only reporting login is mandatory.
 */
public final class SqlReadOnlyGuardrail {
    public static final Map<String, Set<String>> SCHEMA = Map.of(
            "report_income", Set.of("day", "category", "payment_method", "amount_bs", "movement_count"),
            "report_orders", Set.of("status", "order_count", "total_bs", "balance_bs"),
            "report_services", Set.of("code", "price_bs", "active"));
    private static final String ID = "[a-z_]+";
    private static final Pattern SELECT = Pattern.compile(
            "\\ASELECT (.+?) FROM (report_[a-z]+)(?: WHERE (.+?))?(?: GROUP BY (.+?))?(?: ORDER BY (.+?))?(?: LIMIT ([0-9]{1,3}))?\\z",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(drop|delete|update|insert|alter|into|union|with|grant|revoke|truncate|create|call|execute|exec|copy|lock|for|merge|do|set|vacuum|information_schema|dblink[a-z0-9_]*|pg_[a-z0-9_]*|lo_[a-z0-9_]*)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AGGREGATE = Pattern.compile("(sum|count|avg|min|max)\\(([a-z_]+|\\*)\\)");
    private static final Pattern FILTER = Pattern.compile(
            "([a-z_]+) (<=|>=|<>|=|<|>) (-?[0-9]{1,12}(?:\\.[0-9]{1,2})?|true|false|'[a-zA-Z0-9_ -]{1,50}')",
            Pattern.CASE_INSENSITIVE);

    public String validate(String input) {
        if (input == null || input.length() > 4000 || input.chars().anyMatch(c -> c < 32 || c > 126)) {
            throw denied();
        }
        String sql = input.trim();
        if (FORBIDDEN.matcher(sql).find() || sql.contains(";") || sql.contains("--") || sql.contains("/*")
                || sql.contains("*/") || sql.contains("#")) {
            throw denied();
        }
        var match = SELECT.matcher(sql);
        if (!match.matches()) { throw denied(); }
        Set<String> columns = SCHEMA.get(match.group(2).toLowerCase(Locale.ROOT));
        if (columns == null) { throw denied(); }
        String projection = match.group(1).toLowerCase(Locale.ROOT);
        if (!projection.equals("*")) {
            String[] items = projection.split(",", -1);
            if (items.length > 16) { throw denied(); }
            for (String item : items) {
                String expression = item.trim();
                var aggregate = AGGREGATE.matcher(expression);
                if (!columns.contains(expression) && !(aggregate.matches()
                        && (columns.contains(aggregate.group(2))
                        || aggregate.group(1).equals("count") && aggregate.group(2).equals("*")))) {
                    throw denied();
                }
            }
        }
        if (match.group(3) != null) {
            for (String filter : match.group(3).split("(?i) AND ", -1)) {
                var condition = FILTER.matcher(filter);
                if (!condition.matches() || !columns.contains(condition.group(1).toLowerCase(Locale.ROOT))) {
                    throw denied();
                }
            }
        }
        if (match.group(4) != null) { validateColumns(match.group(4), columns, false); }
        if (match.group(5) != null) { validateColumns(match.group(5), columns, true); }
        if (match.group(6) == null) {
            sql = sql + " LIMIT 500";
        } else {
            int limit = Integer.parseInt(match.group(6));
            if (limit < 1 || limit > 500) { throw denied(); }
        }
        return sql;
    }

    private void validateColumns(String list, Set<String> columns, boolean order) {
        for (String item : list.toLowerCase(Locale.ROOT).split(",", -1)) {
            String column = item.trim();
            if (order) { column = column.replaceFirst(" (asc|desc)$", ""); }
            if (!column.matches(ID) || !columns.contains(column)) { throw denied(); }
        }
    }

    private BusinessRuleException denied() {
        return new BusinessRuleException("Consulta de reporte no permitida");
    }
}
