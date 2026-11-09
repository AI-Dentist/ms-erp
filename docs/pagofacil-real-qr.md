# PagoFácil QR Master v1.1.0

Real transport is opt-in: set `PAGOFACIL_MOCK_ENABLED=false` and supply both provider tokens externally. Never paste tokens into source, logs, commands, screenshots or reports. Missing either token fails closed; default mock mode still produces the deliberately non-scannable `MOCK-<UUID>` image and retains its local callback secret/protocol.

## Configuration

| Variable | Default / meaning |
| --- | --- |
| `PAGOFACIL_BASE_URL` | `https://masterqr.pagofacil.com.bo/api/services/v2` |
| `PAGOFACIL_TOKEN_SERVICE` | Empty; login `tcTokenService` header |
| `PAGOFACIL_TOKEN_SECRET` | Empty; `tcTokenSecret` header for login and transaction queries |
| `PAGOFACIL_PAYMENT_METHOD_ID` | `0`; when <=0 discover the first enabled BOB QR service, otherwise first list entry; use and cache `34` if discovery fails or is empty |
| `PAGOFACIL_CALLBACK_URL` | Blank, `localhost` or `127.0.0.1` host uses `https://masterqr.pagofacil.com.bo/callback/placeholder`; configure a reachable HTTPS URL for real callbacks |
| `PAGOFACIL_MOCK_ENABLED` | `true`; explicit `false` is required even when tokens exist |

The adapter POSTs `{}` to `/login`, caches Bearer credentials until five minutes before expiry, discovers/caches the payment method through `/list-enabled-services`, and POSTs `/generate-qr`. It sends `Response-Language: es` and JSON throughout, uses bounded connect/request timeouts and never follows redirects. Non-HTTPS transport is accepted only on loopback for local protocol tests. The service token is sent only to login; the secret is sent to login and transaction queries. Queries retain both reference fields and also send numeric ASCII-only references as `tnTransaccionPF`; query `values` may be an object or a JSON-encoded string. Upstream bodies/messages are not exposed in errors. There is no automatic payment/generation retry.

## Deliberate amount policy (S19 / L27)

**This is not economically equivalent to full-price payment.** At the provider boundary only, divide the original installment by 1000, round HALF_UP to two decimal places and floor positive amounts at Bs 0.01. For example ERP Bs 350.00 generates a real Bs 0.35 charge; Bs 0.01 generates Bs 0.01. The ERP installment, balance, cash movement and receipt retain the original full amount when the verified provider payment settles it. This requested demonstration pricing must not be mistaken for collecting the full invoice value in normal production accounting.

The QR image is stored as pure PNG Base64, never a data-URI prefix. Provider expiration timestamps without offsets use Bolivia time (`America/La_Paz`); offset-bearing timestamps retain their instant. Missing expiry uses the existing ERP expiry configuration. Invalid protocol envelopes, PNG data, payment methods or provider dates fail without manufacturing a mock result.

## Confirmation & Active Verification

Two confirmation mechanisms are supported while preserving pessimistic order-then-QR lock ordering:

1. **Active Verification via GraphQL:** `verificarPagoQrPagoFacil(pagofacilTransactionId: String!): Recibo` allows authenticated `ADMIN` and `CAJERO` users to re-query the provider's `/query-transaction` endpoint directly. This enables frontend check buttons without exposing a public webhook tunnel to localhost. When a transaction is already confirmed, it returns the existing receipt immediately without contacting the provider.
2. **Dual Webhook Dispatcher (`POST /api/v1/webhooks/pagofacil`):**
   - **Local Mock Callback:** Requires header `X-Local-Mock-Secret` and payload `{transactionId, amountBs, confirmedAt}`.
   - **Official PagoFácil Callback:** Receives official provider notifications `{PedidoID, Fecha, Hora, MetodoPago, Estado}`. Rather than trusting callback body fields, the service uses `PedidoID` (the persisted company transaction reference) to perform an authenticated active query against `/query-transaction`. If the provider confirms payment with valid amount and currency, the installment settles and the exact provider envelope `{"error":0,"status":1,"message":"Pago confirmado correctamente","values":true}` is returned.

Reference: `ServiciosPagoFacil_QrMaster_v1.1.0.pdf` (2025-07-02). Protocol checks use a synthetic local HTTP server, not real provider credentials or proof of live provider acceptance.