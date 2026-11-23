package io.prsentinel.agent;

import io.prsentinel.config.SentinelProperties;
import java.math.BigDecimal;

/**
 * Compteur de consommation d'une revue, alimenté à chaque appel au modèle (tours d'outils compris) :
 * il reste juste même si le triage échoue en cours de route, ce qui garde le plafond quotidien fiable.
 */
public class UsageMeter {

    private final SentinelProperties.Agent cfg;
    private long tokensIn;
    private long tokensOut;
    private BigDecimal cost = BigDecimal.ZERO;
    private int calls;

    public UsageMeter(SentinelProperties.Agent cfg) {
        this.cfg = cfg;
    }

    public synchronized void record(String model, long in, long out) {
        tokensIn += in;
        tokensOut += out;
        calls++;
        cost = cost.add(Pricing.cost(cfg, model, in, out));
    }

    public synchronized long tokensIn() {
        return tokensIn;
    }

    public synchronized long tokensOut() {
        return tokensOut;
    }

    public synchronized BigDecimal cost() {
        return cost;
    }

    public synchronized int calls() {
        return calls;
    }
}
