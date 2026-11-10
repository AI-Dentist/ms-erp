# MS3 patient spending: available MS1 evidence

The existing authenticated GraphQL query provides **nominal paid-installment history**, not a provable actual cash total for nonempty histories. In particular, PagoFácil demonstration charges can differ from the nominal amount recorded in the installment, cash movement and receipt. Restoring Keycloak alone does not expose the actual QR charge through this query. No new API or authorization expansion is proposed here.

## Read the existing history

Forward the current caller's Bearer token; `listarOrdenesCobro` permits ADMIN, CAJERO or RECEPCIONISTA. Do not bypass authentication or expand roles. Omit the order-state filter so partially paid orders remain included:

```graphql
query PaidInstallmentHistory($pacienteId: UUID!) {
  listarOrdenesCobro(pacienteId: $pacienteId) {
    id
    paciente { id }
    cuotas { id montoCuotaBs estado fechaPago }
  }
}
```

Select installments with `estado: PAGADA` (the order enum is `PAGADO`, not `PAGADA`). Their `montoCuotaBs` is nominal Bs, not necessarily cash received. The API exposes no pagination or completeness/snapshot marker. Validate the complete response, every returned patient UUID against the requested UUID, the expected shape and errors, and finite nonnegative decimal Bs amounts. A valid empty history is observed zero. Transport failure, 401/403, timeout, GraphQL errors or malformed data means unavailable, never observed zero. When actual spending cannot be established, retain S2's explicitly labeled estimate (existing appointments × Bs 60), including provenance; do not record bearer tokens, payment records or private patient data.

## Why nominal settlement is not actual provider cash

For example, an original Bs 350.00 installment generates a Bs 0.35 PagoFácil provider request, while settlement records a Bs 350.00 movement and receipt. The provider query accepts either scaled or original amount, so neither multiplying nor dividing a receipt reliably establishes the amount actually charged. The query does not expose payment method, receipt or provider amount. `Recibo` exposes neither method nor provider reference; `MovimientoCaja` exposes method through a date-range query, not a patient-linked public query, and its amount is not proof of the provider charge. Do not join patients using fiscal CI/NIT or razón social: these are not guaranteed patient identity.

## Source evidence

Line references below are relative to MS1 source revision `f7847b2`:

- `src/main/resources/graphql/schema.graphqls:24-27,108-110,203-270`: query, enums, installment, movement and receipt fields.
- `src/main/java/com/dentalplatform/erp/financebilling/presentation/graphql/FinanceBillingGraphQLController.java:20-21,40-44,58-62,82-83,105-115`: roles, queries and amount mappings.
- `src/main/java/com/dentalplatform/erp/financebilling/application/usecase/RegistrarCobroCuotaService.java:51-63`: settles the installment and records its nominal amount in the movement and receipt.
- `src/main/java/com/dentalplatform/erp/financebilling/domain/model/Recibo.java:9-15`: receipt copies the movement amount.
- `src/main/java/com/dentalplatform/erp/financebilling/application/port/PagoFacilGatewayPort.java:17-21`: divide by 1000, round HALF_UP to two decimals, minimum Bs 0.01.
- `src/main/java/com/dentalplatform/erp/financebilling/infrastructure/external/PagoFacilMasterQrClient.java:43-51`: sends the scaled amount to the provider.
- `src/main/java/com/dentalplatform/erp/financebilling/application/usecase/VerificarPagoQrPagoFacilUseCase.java:50-62,76-79`: accepts scaled or original queried amount and settles via the nominal payment service.
