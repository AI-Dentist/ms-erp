package com.dentalplatform.erp.financebilling.application.agent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Category of an ERP agent tool; the source of {@code McpToolMetadata.categoria}. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolCategory {
    String MONITOREO = "MONITOREO";
    String ACCION_CRUD = "ACCION_CRUD";
    String REPORTE = "REPORTE";
    String COBRANZA = "COBRANZA";

    String value();
}
