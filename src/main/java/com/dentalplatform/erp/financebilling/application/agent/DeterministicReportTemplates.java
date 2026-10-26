package com.dentalplatform.erp.financebilling.application.agent;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;

/**
 * Closed-vocabulary fallback, not a natural-language model. Unknown words/constraints are rejected rather than
 * silently dropped. Payment filters map to fixed enum literals, never interpolated user SQL. Every generated
 * template still passes the SQL guardrail and the dedicated read-only reporting connection.
 */
public final class DeterministicReportTemplates {
    private static final Set<String> FILLER = Set.of("genera", "generar", "dame", "quiero", "muestra", "muestrame",
            "un", "una", "el", "la", "los", "las", "reporte", "reportes", "informe", "informes", "grafico",
            "de", "del", "por", "favor", "sobre", "total", "totales");
    private static final Set<String> SERVICES = Set.of("servicio", "servicios", "precio", "precios", "arancel", "aranceles");
    private static final Set<String> ORDERS = Set.of("orden", "ordenes", "estado", "estados", "saldo", "saldos");
    private static final Set<String> INCOME = Set.of("ingreso", "ingresos", "cobro", "cobros", "venta", "ventas",
            "metodo", "metodos", "forma", "formas", "pago", "dia", "dias", "diario", "diarios", "fecha", "fechas",
            "evolucion", "categoria", "categorias", "solo", "qr", "qr_pagofacil", "efectivo", "transferencia");

    private DeterministicReportTemplates() { }

    public static String resolver(String request, LocalDate today) {
        String text = Normalizer.normalize(request, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[¿?¡!.,]", " ").strip();
        if (text.contains("servicio") || text.contains("precio") || text.contains("arancel")) {
            requireVocabulary(text, SERVICES);
            return "SELECT code, price_bs FROM report_services WHERE active = true ORDER BY code ASC LIMIT 100";
        }
        if (text.contains("orden") || text.contains("estado") || text.contains("saldo")) {
            requireVocabulary(text, ORDERS);
            return "SELECT status, SUM(order_count), SUM(total_bs), SUM(balance_bs) FROM report_orders "
                    + "GROUP BY status ORDER BY status ASC LIMIT 20";
        }
        if (text.contains("ingreso") || text.contains("cobro") || text.contains("venta")) {
            requireVocabulary(text, INCOME);
            Set<String> words = Set.copyOf(java.util.List.of(text.split("\\s+")));
            String method = null;
            for (String candidate : new String[]{"qr_pagofacil", "efectivo", "transferencia"}) {
                if (words.contains(candidate) || candidate.equals("qr_pagofacil") && words.contains("qr")) {
                    if (method != null) { throw unsupported(); }
                    method = candidate.toUpperCase(Locale.ROOT);
                }
            }
            if (words.contains("solo") && method == null) { throw unsupported(); }
            String predicate = method == null ? "" : " WHERE payment_method = '" + method + "'";
            if (text.contains("metodo") || text.contains("forma")) {
                return "SELECT payment_method, SUM(amount_bs) FROM report_income" + predicate
                        + " GROUP BY payment_method ORDER BY payment_method ASC LIMIT 20";
            }
            if (text.contains("dia") || text.contains("fecha") || text.contains("evolucion")) {
                predicate += (predicate.isEmpty() ? " WHERE" : " AND") + " day >= '" + today.minusDays(90) + "'";
                return "SELECT day, SUM(amount_bs) FROM report_income" + predicate
                        + " GROUP BY day ORDER BY day ASC LIMIT 100";
            }
            return "SELECT category, SUM(amount_bs) FROM report_income" + predicate
                    + " GROUP BY category ORDER BY category ASC LIMIT 50";
        }
        throw unsupported();
    }

    private static void requireVocabulary(String text, Set<String> allowed) {
        for (String word : text.split("\\s+")) {
            if (!FILLER.contains(word) && !allowed.contains(word)) { throw unsupported(); }
        }
    }

    private static BusinessRuleException unsupported() {
        return new BusinessRuleException("El respaldo determinístico solo arma reportes de ingresos (por categoría, "
                + "método de pago o día), órdenes por estado y precios de servicios; no puede representar todos los filtros "
                + "de esta solicitud. Simplifique la instrucción o configure un proveedor de IA");
    }
}
