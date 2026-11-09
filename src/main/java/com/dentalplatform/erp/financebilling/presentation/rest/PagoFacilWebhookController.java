package com.dentalplatform.erp.financebilling.presentation.rest;

import com.dentalplatform.erp.financebilling.application.usecase.*;
import com.dentalplatform.erp.financebilling.domain.model.Recibo;
import com.dentalplatform.erp.shared.domain.exception.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Supports both local mock callback with X-Local-Mock-Secret header
 * and official PagoFacil QR Master callback ({PedidoID, Fecha, Hora, MetodoPago, Estado})
 * which verifies transaction actively via POST /query-transaction.
 */
@RestController
@RequestMapping("/api/v1/webhooks/pagofacil")
public class PagoFacilWebhookController {
    private final ConfirmarPagoQrWebhookUseCase confirm;
    private final VerificarPagoQrPagoFacilUseCase verify;
    private final String secret;

    public PagoFacilWebhookController(ConfirmarPagoQrWebhookUseCase confirm,
                                      String secret) {
        this(confirm, null, secret);
    }

    @Autowired
    public PagoFacilWebhookController(ConfirmarPagoQrWebhookUseCase confirm,
                                      VerificarPagoQrPagoFacilUseCase verify,
                                      @Value("${erp.pagofacil.webhook-secret:}") String secret) {
        this.confirm = confirm;
        this.verify = verify;
        this.secret = secret;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> confirmar(@RequestHeader(value = "X-Local-Mock-Secret", required = false) String supplied,
                                        @RequestBody JsonNode body) {
        if (body != null && body.hasNonNull("PedidoID")) {
            if (verify == null) { throw new UnsupportedPagoFacilIntegrationException(); }
            String pedidoId = body.path("PedidoID").asText().trim();
            if (pedidoId.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "PedidoID requerido"));
            }
            try {
                verify.ejecutar(pedidoId);
                return ResponseEntity.ok(Map.of("error", 0, "status", 1, "message", "Pago confirmado correctamente", "values", true));
            } catch (BusinessRuleException | ResourceNotFoundException e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
            }
        }
        if (body != null && body.hasNonNull("transactionId")) {
            String txId = body.path("transactionId").asText();
            BigDecimal amount = null;
            if (body.hasNonNull("amountBs")) {
                try {
                    amount = new BigDecimal(body.path("amountBs").asText().trim());
                } catch (NumberFormatException e) {
                    throw new BusinessRuleException("Monto inválido");
                }
            }
            OffsetDateTime confirmedAt = null;
            if (body.hasNonNull("confirmedAt")) {
                try {
                    confirmedAt = OffsetDateTime.parse(body.path("confirmedAt").asText().trim());
                } catch (Exception e) {
                    throw new BusinessRuleException("Fecha de confirmación inválida");
                }
            }
            return confirmar(supplied, new Callback(txId, amount, confirmedAt));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Formato de webhook no reconocido"));
    }

    public ResponseEntity<?> confirmar(String supplied, Callback body) {
        if (body == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Formato de webhook no reconocido"));
        }
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