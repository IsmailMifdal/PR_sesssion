package io.prsentinel.agent;

import io.prsentinel.config.SentinelProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Coût d'un appel : tokens × prix du modèle (en $ par million de tokens). */
public final class Pricing {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private Pricing() {}

    public static BigDecimal cost(SentinelProperties.Agent agent, String model, long tokensIn, long tokensOut) {
        boolean strong = model.equals(agent.strongModel());
        BigDecimal in = strong ? agent.strongInputPricePerMillion() : agent.inputPricePerMillion();
        BigDecimal out = strong ? agent.strongOutputPricePerMillion() : agent.outputPricePerMillion();
        return in.multiply(BigDecimal.valueOf(tokensIn)).add(out.multiply(BigDecimal.valueOf(tokensOut)))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }
}
