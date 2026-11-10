package com.dentalplatform.erp.financebilling.application.report.manual;

import java.util.List;

/** Server-side whitelist entry for the manual report builder (GraphQL {@code EntidadReporte}). */
public record EntidadReporte(String clave, String nombre, String origen, List<ColumnaReporte> columnas,
                             List<String> filtrosEstado, boolean filtroFecha) {
    public EntidadReporte {
        columnas = List.copyOf(columnas);
        filtrosEstado = List.copyOf(filtrosEstado);
    }

    public boolean esMs3() { return "MS3".equals(origen); }
}
