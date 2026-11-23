package io.prsentinel.scan;

/** Alerte normalisée d'un scanner. {@code line} vaut {@code null} quand l'outil ne donne pas de ligne. */
public record Finding(String tool, String ruleId, String severity, String path, Integer line, String message) {}
