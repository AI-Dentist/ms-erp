# MS3 patient spending: available MS1 evidence

Use the additive authenticated `obtenerGastoPagadoPaciente` query for an attributable, complete cash-ledger total. Its result is not a bank confirmation and cannot establish an actual QR provider charge. Forward the current caller's Bearer token; the existing financial read roles are ADMIN, CAJERO and RECEPCIONISTA. No authorization expansion is provided.

```graphql
query PaidSpending($pacienteId: UUID!) {
  obtenerGastoPagadoPaciente(pacienteId: $pacienteId) {
    pacienteId moneda completo totalPagadoBs
  }
}
```

The argument is `UUID!`; the result is `GastoPagadoPaciente!` with `pacienteId: UUID!`, `moneda: String!` (always `BOB`), `completo: Boolean!` and nullable `totalPagadoBs: BigDecimal`. A nonexistent patient produces an error; inactive patients retain their history. A valid empty or unpaid history is complete with zero. For complete paid history, the total sums only consistent cash payments, including paid installments in partially paid orders.

The read uses a single database snapshot, linking patient UUID to orders, installments, cash movements and receipt rows. It executes with `@Transactional(readOnly = true)` using a static single `SELECT` without calling sequence generators (`nextval('recibo_numero_seq')` is never invoked) or mutating financial records. It checks plan counts and balances, statuses, duplicate quota/movement/receipt IDs, valid positive finite amounts for every attributable quota (both paid and unpaid), cash method, income/category, payment date presence (`fechaPagoPresente`), and matching payment, movement and receipt timestamps. An empty sentinel requires a single consistent row without orphan quota or receipt identifiers. Missing, contradictory or duplicate evidence, non-finite/negative amounts, QR or any unsupported payment method returns `completo: false` and `totalPagadoBs: null`; no partial subtotal is returned. A missing PDF does not invalidate an existing receipt row.

MS3 must validate the whole GraphQL response, returned patient UUID, `BOB`, boolean completeness and finite nonnegative total. On incomplete data, errors, malformed response, 401/403, timeout or transport failure, retain the explicitly labeled appointments × Bs 60 estimate and its provenance; never interpret failure as zero. Do not log tokens, payment records or patient data. Unlinked movements (`cuota_id` null) cannot reliably be associated with a patient; never infer that association from fiscal CI/NIT or name. Ledger consistency establishes only recorded attributable cash, not physical cash or bank proof.

## Previous nominal history query

The existing query below remains available for history but is superseded for actual cash reads. PagoFácil demonstration charges can differ from nominal installment, movement and receipt amounts. Restoring Keycloak alone cannot establish the actual QR charge.

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

For example, an original Bs 350.00 installment generates a Bs 0.35 PagoFácil provider request, while settlement records a Bs 350.00 movement and receipt. The provider query accepts either scaled or original amount, so neither multiplying nor dividing a receipt reliably establishes the amount actually charged. The previous history query does not expose payment method, receipt or provider amount. `Recibo` exposes neither method nor provider reference; `MovimientoCaja` exposes method through a date-range query, not a patient-linked public query, and its amount is not proof of the provider charge. Do not join patients using fiscal CI/NIT or razón social: these are not guaranteed patient identity.

## Source evidence

Line references below are relative to MS1 source revision `f7847b2`:

- `src/main/resources/graphql/schema.graphqls:24-27,108-110,203-270`: query, enums, installment, movement and receipt fields.
- `src/main/java/com/dentalplatform/erp/financebilling/presentation/graphql/FinanceBillingGraphQLController.java:20-21,40-44,58-62,82-83,105-115`: roles, queries and amount mappings.
- `src/main/java/com/dentalplatform/erp/financebilling/application/usecase/RegistrarCobroCuotaService.java:51-63`: settles the installment and records its nominal amount in the movement and receipt.
- `src/main/java/com/dentalplatform/erp/financebilling/domain/model/Recibo.java:9-15`: receipt copies the movement amount.
- `src/main/java/com/dentalplatform/erp/financebilling/application/port/PagoFacilGatewayPort.java:17-21`: divide by 1000, round HALF_UP to two decimals, minimum Bs 0.01.
- `src/main/java/com/dentalplatform/erp/financebilling/infrastructure/external/PagoFacilMasterQrClient.java:43-51`: sends the scaled amount to the provider.
- `src/main/java/com/dentalplatform/erp/financebilling/application/usecase/VerificarPagoQrPagoFacilUseCase.java:50-62,76-79`: accepts scaled or original queried amount and settles via the nominal payment service.
