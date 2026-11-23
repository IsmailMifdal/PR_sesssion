package io.prsentinel.agent;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** Verdict structuré rendu par le modèle pour une alerte. Jamais de texte libre. */
public record Verdict(
        @JsonPropertyDescription("VRAI_PROBLEME, FAUX_POSITIF ou A_VERIFIER") Decision decision,
        @JsonPropertyDescription("CRITIQUE, HAUTE, MOYENNE ou BASSE") Gravite gravite,
        @JsonPropertyDescription("2 phrases maximum, compréhensibles par un développeur junior") String explication,
        @JsonPropertyDescription("Lignes de remplacement pour la ligne signalée (code seul, sans diff), ou null")
                String correctif,
        @JsonPropertyDescription("Confiance entre 0 et 1") double confiance) {

    public enum Decision { VRAI_PROBLEME, FAUX_POSITIF, A_VERIFIER }

    public enum Gravite { CRITIQUE, HAUTE, MOYENNE, BASSE }

    public static final int MAX_EXPLANATION_CHARS = 600;
    public static final int MAX_FIX_CHARS = 2_000;

    /** Rejette une réponse incomplète ou hors bornes (elle sera relancée une fois). */
    public void validate() {
        if (decision == null || gravite == null) {
            throw new InvalidVerdictException("décision ou gravité manquante");
        }
        if (explication == null || explication.isBlank() || explication.length() > MAX_EXPLANATION_CHARS) {
            throw new InvalidVerdictException("explication absente ou trop longue");
        }
        if (Double.isNaN(confiance) || confiance < 0 || confiance > 1) {
            throw new InvalidVerdictException("confiance hors de [0, 1]");
        }
        if (correctif != null && correctif.length() > MAX_FIX_CHARS) {
            throw new InvalidVerdictException("correctif trop long");
        }
    }

    public boolean blocking() {
        return decision == Decision.VRAI_PROBLEME && gravite == Gravite.CRITIQUE;
    }

    public static class InvalidVerdictException extends RuntimeException {
        public InvalidVerdictException(String message) {
            super(message);
        }
    }
}
