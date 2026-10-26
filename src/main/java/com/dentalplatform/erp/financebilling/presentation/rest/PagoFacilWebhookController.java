package com.dentalplatform.erp.financebilling.presentation.rest;

import com.dentalplatform.erp.financebilling.application.usecase.*;
import com.dentalplatform.erp.financebilling.domain.model.Recibo;
import com.dentalplatform.erp.shared.domain.exception.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * LOCAL MOCK protocol only; this is NOT PagoFacil's wire format or signature scheme.
 * POST /api/v1/webhooks/pagofacil, Content-Type: application/json,
 * X-Local-Mock-Secret: configured erp.pagofacil.webhook-secret (blank/change-me never accepted).
 * Body: {"transactionId":"MOCK-<UUID>","amountBs":100.00,"confirmedAt":"2026-10-22T10:00:00-04:00"}.
 * No client cuota/cashier/customer fields are read. Amount must equal the stored amount exactly in value;
 * confirmedAt must be within the transaction lifetime (60s clock skew), not more than 60s in the future.
 * 200: stable receipt summary; 400: invalid reference/amount/time; 401: secret invalid;
 * 404: unknown transaction; 410: expired; 503: real provider unsupported.
 * Idempotency-Key is intentionally ignored; confirmed transaction IDs return the same receipt.
 */
@RestController
@RequestMapping("/api/v1/webhooks/pagofacil")
public class PagoFacilWebhookController {
    private final ConfirmarPagoQrWebhookUseCase confirm;
    private final String secret;

    public PagoFacilWebhookController(ConfirmarPagoQrWebhookUseCase confirm,
                                      @Value("${erp.pagofacil.webhook-secret:}") String secret) {
        this.confirm = confirm;
        this.secret = secret;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> confirmar(@RequestHeader(value = "X-Local-Mock-Secret", required = false) String supplied,
                                        @RequestBody Callback body) {
        if (secret == null || secret.isBlank() || "change-me".equalsIgnoreCase(secret.trim()) || supplied == null
                || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Credencial mock inválida"));
        }
        Recibo receipt = confirm.ejecutar(body.transactionId(), body.amountBs(), body.confirmedAt());
        return ResponseEntity.ok(new ReceiptResponse(receipt.id(), receipt.numeroRecibo(), receipt.ciNitCliente(),
                receipt.razonSocialCliente(), receipt.montoTotal().amount()));
    }

    @ExceptionHandler(UnsupportedPagoFacilIntegrationException.class)
    ResponseEntity<?> unsupported(UnsupportedPagoFacilIntegrationException e) { return error(503, e); }
    @ExceptionHandler(QrExpiredException.class)
    ResponseEntity<?> expired(QrExpiredException e) { return error(410, e); }
    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<?> missing(ResourceNotFoundException e) { return error(404, e); }
    @ExceptionHandler(BusinessRuleException.class)
    ResponseEntity<?> invalid(BusinessRuleException e) { return error(400, e); }

    private ResponseEntity<?> error(int status, RuntimeException e) {
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }

    public record Callback(String transactionId, BigDecimal amountBs, OffsetDateTime confirmedAt) { }
    public record ReceiptResponse(UUID id, String numeroRecibo, String ciNitCliente,
                                  String razonSocialCliente, BigDecimal montoTotalBs) { }
}
