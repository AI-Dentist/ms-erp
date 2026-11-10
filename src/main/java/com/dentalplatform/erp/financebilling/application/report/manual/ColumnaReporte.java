package com.dentalplatform.erp.financebilling.application.report.manual;

/** One selectable column. {@code tipo} is TEXTO, NUMERO, MONEDA_BS, FECHA, FECHA_HORA or BOOLEANO. */
public record ColumnaReporte(String clave, String etiqueta, String tipo) { }
