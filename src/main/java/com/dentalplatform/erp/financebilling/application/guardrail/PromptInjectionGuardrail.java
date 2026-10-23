package com.dentalplatform.erp.financebilling.application.guardrail;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic, bounded defense in depth, NOT an authorization boundary or a complete jailbreak detector.
 * Tools must retain the caller's authentication and enforce roles, domain rules, transactions and audit.
 * No external calls; errors never echo user input. Call before sending instructions to any model.
 */
public final class PromptInjectionGuardrail {
    private static final int MAX_LENGTH = 2000;
    private static final Pattern ATTACK = Pattern.compile(String.join("|",
            "\\b(?:ignora|olvida|omite) (?:todas? )?(?:las? )?instrucciones (?:anteriores|previas)\\b",
            "\\bignore (?:all )?previous instructions\\b",
            "\\bsystem prompt\\b", "\\b(?:eres ahora|act as|you are now)\\b",
            "\\b(?:modo desarrollador|developer mode|dan|jailbreak)\\b",
            "< */? *system *>", "\\[ */? *inst *\\]", "###",
            "\\b(?:revela|revelar|muestra|mostrar|dime|reveal|show|print|expose|disclose)\\b.{0,100}\\b(?:prompt|claves?|keys?|secretos?|secrets?|credenciales|credentials|api key|tokens?)\\b",
            "\\b(?:sin validar|salt(?:a|ar|ate) (?:las )?validaciones|aproba(?:r)? sin)\\b",
            "\\bmodifica(?:r)? (?:el )?saldo directamente\\b",
            "\\bejecuta(?:r)? (?:este )?sql\\b",
            "\\b(?:update|delete)\\b.{0,100}\\b(?:directamente|directly)\\b",
            "\\bdesactiva(?:r)? (?:la )?auditoria\\b",
            "\\b(?:disable|bypass|skip) (?:all |the )?(?:financial )?(?:audit|auditing|rules|validation|validations)\\b",
            "\\bapprove without\\b", "\\bmodify (?:the )?balance directly\\b", "\\bexecute (?:this )?sql\\b"));

    public void validar(String instruccion) {
        if (instruccion == null || instruccion.isBlank() || instruccion.length() > MAX_LENGTH) {
            throw new PromptInjectionException();
        }
        String normalized = Normalizer.normalize(instruccion, Normalizer.Form.NFKC)
                .replaceAll("(?U)\\s", " ")
                .replaceAll("\\p{C}", "");
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("(?U)\\s+", " ").strip().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > MAX_LENGTH || ATTACK.matcher(normalized).find()) {
            throw new PromptInjectionException();
        }
    }
}
