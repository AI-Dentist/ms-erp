# Financial KPIs (`obtenerKpisFinancieros`)

One read-only GraphQL query returns the five financial executive KPIs for an inclusive period: income, overdue installments, most requested services, net cash flow and collection by payment method. Agenda and stock KPIs are served by MS3, not here.

```graphql
query FinancialKpis($inicio: DateTime!, $fin: DateTime!) {
  obtenerKpisFinancieros(fechaInicio: $inicio, fechaFin: $fin) {
    fechaInicio fechaFin
    ingresos { totalBs cantidadMovimientos montoNominalQrBs seriePorDia { fecha montoBs } }
    morosidad { cuotasVencidas cuotasExigibles porcentaje montoVencidoBs
                detalle { cuotaId ordenCobroId pacienteId pacienteNombre numeroCuota montoBs fechaVencimiento diasAtraso } }
    serviciosMasSolicitados { servicioId nombre cantidad montoBs }
    flujoCaja { totalIngresosBs totalEgresosBs saldoNetoBs seriePorDia { fecha ingresosBs egresosBs saldoBs } }
    cobranzaPorMetodo { metodoPago cantidad montoBs porcentajeMonto montoNominal }
  }
}
```

## Access and period

| Topic | Rule |
| --- | --- |
| Roles | `ADMIN` only. Anonymous requests get HTTP 401; any other role gets GraphQL error `FORBIDDEN`. This is deliberately narrower than `obtenerFlujoCaja` (ADMIN/CAJERO/RECEPCIONISTA), which is unchanged. |
| Period | Both bounds are inclusive. `fechaInicio` after `fechaFin`, or a duration longer than 366 days, returns GraphQL error `BAD_REQUEST`. |
| Calendar | Daily series and installment due dates use `America/La_Paz` calendar dates. |
| Consistency | All KPIs in one response come from one read-only REPEATABLE READ snapshot. The query writes nothing. |
| Empty period | Zeros and empty lists, never invented values. `cobranzaPorMetodo` still lists every payment method. |

## KPI rules

| KPI | Rule |
| --- | --- |
| `ingresos` | `INGRESO` cash movements in the period. `montoNominalQrBs` is the `QR_PAGOFACIL` part. `seriePorDia` has one entry per day that has income movements. |
| `morosidad` | Evaluated at `min(fechaFin, now)`. Exigibles: installments not `ANULADA` whose due date falls in the period. Vencidas: exigibles still `PENDIENTE` or `VENCIDA` whose due date is before the evaluation date (no process sets `VENCIDA`, so overdue is computed). `porcentaje` = vencidas / exigibles × 100, 2 decimals, 0 when there are no exigibles. `detalle` is sorted by `diasAtraso` descending, then installment id. |
| `serviciosMasSolicitados` | Items of orders not `ANULADO` created in the period, grouped by service. Top 10 by `cantidad`, then `montoBs` (item subtotal), then service id. |
| `flujoCaja` | Totals equal `obtenerFlujoCaja` for the same period (computed by the same use case). `seriePorDia` has one entry per day with any movement; `saldoBs` is that day's income minus expenses. |
| `cobranzaPorMetodo` | `INGRESO` movements grouped by `metodoPago`, every enum value present in enum order (`EFECTIVO`, `QR_PAGOFACIL`, `TRANSFERENCIA`). `porcentajeMonto` is over the period income total, 2 decimals, 0 when the total is 0. |

Monetary values are Bs with scale 2; percentages are rounded HALF_UP to 2 decimals.

## QR amounts are nominal

`montoNominalQrBs` and the `QR_PAGOFACIL` entry (`montoNominal: true`) report the **nominal installment amount** recorded by the ERP. The PagoFácil demonstration charges the provider `amount / 1000`, so these figures are not the money the provider actually collected. Display this note next to income and collection-by-method KPIs. See [PagoFácil QR](pagofacil-real-qr.md) and [MS3 paid spending](ms3-paid-spending-contract.md) for the same caveat.
