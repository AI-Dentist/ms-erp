package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.financebilling.application.agent.NotificacionMulticanalPayload;
import com.dentalplatform.erp.financebilling.application.agent.NotificacionMulticanalPayload.ResultadoCanalNotificacion;
import com.dentalplatform.erp.financebilling.application.port.MultichannelNotificationPort;
import com.dentalplatform.erp.financebilling.application.port.MultichannelNotificationPort.*;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class DispararNotificacionMulticanalUseCase {
    private static final Logger log = LoggerFactory.getLogger(DispararNotificacionMulticanalUseCase.class);
    private static final List<String> ALL = List.of("WHATSAPP", "TELEGRAM", "GMAIL");
    private static final Map<String,String> PROVIDERS = Map.of("WHATSAPP", "META_CLOUD_TEST_NUMBER",
            "TELEGRAM", "TELEGRAM_BOT_API", "GMAIL", "GMAIL_SMTP");
    private final MultichannelNotificationPort transport;
    private final Environment environment;

    public DispararNotificacionMulticanalUseCase(MultichannelNotificationPort transport, Environment environment) {
        this.transport = transport;
        this.environment = environment;
    }

    @PreAuthorize("hasAnyRole('ADMIN','CAJERO')")
    public NotificacionMulticanalPayload ejecutar(String pacienteNombre, String telefono, String email, String telegramChatId,
                                                   BigDecimal montoBs, String concepto, String fechaVencimiento, Object canales) {
        var channels = channels(canales);
        String name = required(pacienteNombre, "pacienteNombre", 150);
        String phone = required(telefono, "telefono", 16);
        if (!phone.matches("\\+[1-9][0-9]{7,14}")) { throw invalid("telefono debe usar E.164"); }
        String mail = email == null || email.isBlank() ? "paciente@ejemplo.com" : required(email, "email", 254);
        if (!mail.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+")) { throw invalid("email no válido"); }
        String chat = telegramChatId == null || telegramChatId.isBlank() ? "" : required(telegramChatId, "telegramChatId", 100);
        String detail = required(concepto, "concepto", 500);
        String due = required(fechaVencimiento, "fechaVencimiento", 10);
        try { LocalDate.parse(due); } catch (DateTimeParseException e) { throw invalid("fechaVencimiento debe usar AAAA-MM-DD válido"); }
        if (montoBs == null || montoBs.signum() <= 0 || montoBs.stripTrailingZeros().scale() > 2
                || montoBs.compareTo(new BigDecimal("9999999999.99")) > 0) { throw invalid("montoBs debe ser positivo con hasta dos decimales"); }
        BigDecimal amount = montoBs.setScale(2);
        var recipient = new Destinatario(name, phone, mail, chat);
        var financial = new DatosFinancieros("BOB", "Bs", amount, detail, due);
        String message = "Clínica Dental: Estimado(a) " + name + ", le recordamos que tiene pendiente el pago de Bs "
                + amount.toPlainString() + " por concepto de " + detail + " (Vencimiento: " + due + ").";
        var payload = new Payload("NOTIFICACION_COBRANZA_MULTICANAL", channels, recipient, financial, message);
        boolean demo = environment.acceptsProfiles(Profiles.of("dev", "local"))
                && !environment.acceptsProfiles(Profiles.of("prod", "production"));
        if (!transport.habilitado()) { return unavailable(payload, demo, "DisabledOrUnconfigured"); }
        try {
            transport.enviar(payload);
            return result(payload, true, "N8N_LIVE", "ENVIADO");
        } catch (UnavailableException e) {
            return unavailable(payload, demo, e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            // HTTP rejection/configuration errors are never relabeled as successful simulation.
            return unavailable(payload, false, e.getClass().getSimpleName());
        }
    }

    private NotificacionMulticanalPayload unavailable(Payload payload, boolean demo, String failureClass) {
        String mode = demo ? "SIMULADO_SIN_N8N" : "NO_ENVIADO";
        // Never log message text, financial detail, destinations, names, exceptions or webhook credentials.
        var redacted = Map.of("tipoEvento", payload.tipoEvento(), "canalesActivos", payload.canalesActivos(),
                "destinatario", "[REDACTED]", "datosFinancieros", "[REDACTED]", "mensajeFormateado", "[REDACTED]");
        log.warn("Multichannel notification {} payload={} failureClass={}", mode, redacted, failureClass);
        return result(payload, demo, mode, demo ? "SIMULADO_OK" : "NO_ENVIADO");
    }

    private NotificacionMulticanalPayload result(Payload payload, boolean success, String mode, String state) {
        var recipient = payload.destinatario();
        return new NotificacionMulticanalPayload(success, mode, payload.canalesActivos().stream().map(channel -> {
            String destination = switch (channel) {
                case "WHATSAPP" -> recipient.telefono();
                case "TELEGRAM" -> recipient.telegramChatId().isEmpty() ? "Chat Clínica" : recipient.telegramChatId();
                default -> recipient.email();
            };
            return new ResultadoCanalNotificacion(channel, PROVIDERS.get(channel), state, destination);
        }).toList());
    }

    private static List<String> channels(Object input) {
        if (input == null || input instanceof String s && s.isBlank() || input instanceof List<?> list && list.isEmpty()) { return ALL; }
        List<?> raw;
        if (input instanceof String text) { raw = Arrays.asList(text.split(",", -1)); }
        else if (input instanceof List<?> list) { raw = list; }
        else { throw invalid("canales debe ser una lista, texto separado por comas o TODOS"); }
        var channels = new LinkedHashSet<String>();
        for (Object item : raw) {
            if (!(item instanceof String text)) { throw invalid("canales solo admite texto"); }
            String channel = text.strip().toUpperCase(Locale.ROOT);
            if (channel.equals("TODOS") && raw.size() == 1) { return ALL; }
            if (!ALL.contains(channel)) { throw invalid("canal no permitido"); }
            channels.add(channel);
        }
        return List.copyOf(channels);
    }
    private static String required(String input, String field, int max) {
        if (input == null || input.isBlank() || input.strip().length() > max || input.chars().anyMatch(Character::isISOControl)) {
            throw invalid(field + " es obligatorio y admite hasta " + max + " caracteres sin controles");
        }
        return input.strip();
    }
    private static BusinessRuleException invalid(String message) { return new BusinessRuleException(message); }
}
