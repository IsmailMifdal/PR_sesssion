package io.prsentinel.review;

import io.prsentinel.agent.SecretMasker;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Un correctif proposé par le modèle devient un bloc {@code suggestion} applicable en un clic : il a été
 * écrit après lecture de code non fiable, donc il doit rester petit et ne rien introduire de dangereux.
 * Sinon, on publie l'explication sans suggestion.
 */
public final class SuggestionPolicy {

    static final int MAX_LINES = 6;
    static final int MAX_CHARS = 600;
    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("://"),
            Pattern.compile("(?i)\\b(Runtime\\s*\\.\\s*getRuntime|ProcessBuilder|exec\\s*\\(|eval\\s*\\(|ScriptEngine"
                    + "|System\\s*\\.\\s*(load|loadLibrary|exit)|Class\\s*\\.\\s*forName|ObjectInputStream|URLClassLoader"
                    + "|child_process|Function\\s*\\(|new\\s+Function|innerHTML|dangerouslySetInnerHTML|subprocess|os\\.system)"),
            Pattern.compile("(?m)^\\s*(import|require|#include|using)\\b"),
            Pattern.compile("(?i)\\b(curl|wget|nc|bash|sh)\\s"),
            Pattern.compile("@\\w"));

    private SuggestionPolicy() {}

    /** Le correctif à publier, ou vide s'il est refusé (raison dans les journaux). */
    public static Optional<String> accept(String correctif) {
        if (correctif == null || correctif.isBlank()) {
            return Optional.empty();
        }
        String fix = correctif.replace("\r", "");
        if (fix.length() > MAX_CHARS || fix.split("\n", -1).length > MAX_LINES) {
            return Optional.empty();
        }
        for (Pattern p : FORBIDDEN) {
            if (p.matcher(fix).find()) {
                return Optional.empty();
            }
        }
        if (!new SecretMasker(Map.of()).maskText(fix).equals(fix)) {
            return Optional.empty(); // ne jamais suggérer un secret
        }
        return Optional.of(fix);
    }

}
