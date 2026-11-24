package io.prsentinel.agent;

/** Verdict obtenu, avec la consommation correspondante. */
public record Triage(Verdict verdict, long tokensIn, long tokensOut, String model, int toolCalls) {}
