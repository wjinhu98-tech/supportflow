package dev.supportflow.assistant.runtime.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Charged totals include reservations whose provider outcome is unknown. USD is an estimate at frozen configured rates. */
public record AgentBudget(AgentModelProfile profile, long maxTokens, long chargedTokens, long reportedTokens,
                          BigDecimal maxCostUsd, BigDecimal chargedCostUsd, int inputAllowance, int outputAllowance,
                          BigDecimal inputUsdPerMillion, BigDecimal outputUsdPerMillion) {
    public AgentBudget {
        Objects.requireNonNull(profile);
        if (maxTokens < 1 || maxTokens > 1000000 || chargedTokens < 0 || chargedTokens > maxTokens
                || reportedTokens < 0 || reportedTokens > chargedTokens
                || inputAllowance < 2048 || inputAllowance > 32768 || outputAllowance < 64 || outputAllowance > 1024)
            throw new IllegalArgumentException("Invalid run token budget");
        for (BigDecimal value : new BigDecimal[]{maxCostUsd, chargedCostUsd, inputUsdPerMillion, outputUsdPerMillion}) {
            if (value == null || value.signum() < 0 || value.scale() > 12 || value.compareTo(new BigDecimal("1000")) > 0)
                throw new IllegalArgumentException("Invalid configured model cost");
        }
        if (maxCostUsd.signum() <= 0 || chargedCostUsd.compareTo(maxCostUsd) > 0
                || profile.metered() && (inputUsdPerMillion.signum() <= 0 || outputUsdPerMillion.signum() <= 0))
            throw new IllegalArgumentException("Invalid run cost ceiling");
    }
    public long reservationTokens() { return profile.metered() ? (long) inputAllowance + outputAllowance : 0; }
    public BigDecimal estimate(int input, int output) {
        return inputUsdPerMillion.multiply(BigDecimal.valueOf(input))
                .add(outputUsdPerMillion.multiply(BigDecimal.valueOf(output)))
                .divide(new BigDecimal("1000000"), 12, RoundingMode.CEILING);
    }
    public BigDecimal reservationCost() { return profile.metered() ? estimate(inputAllowance, outputAllowance) : BigDecimal.ZERO; }
    public boolean canReserve() {
        return chargedTokens + reservationTokens() <= maxTokens
                && chargedCostUsd.add(reservationCost()).compareTo(maxCostUsd) <= 0;
    }
    public AgentBudget reserve() {
        if (!canReserve()) throw new IllegalStateException("Run model budget is exhausted");
        return copy(chargedTokens + reservationTokens(), reportedTokens, chargedCostUsd.add(reservationCost()));
    }
    public AgentBudget settle(AgentTokenUsage usage) {
        Objects.requireNonNull(usage);
        if (!profile.metered()) {
            if (usage.total() != 0) throw new IllegalArgumentException("Unmetered planner cannot report provider usage");
            return this;
        }
        if (usage.promptTokens() < 1 || usage.completionTokens() < 1
                || usage.promptTokens() > inputAllowance || usage.completionTokens() > outputAllowance)
            throw new IllegalArgumentException("Provider usage exceeds reserved bounds");
        return copy(chargedTokens - reservationTokens() + usage.total(), reportedTokens + usage.total(),
                chargedCostUsd.subtract(reservationCost()).add(estimate(usage.promptTokens(), usage.completionTokens())));
    }
    private AgentBudget copy(long charged, long reported, BigDecimal cost) {
        return new AgentBudget(profile, maxTokens, charged, reported, maxCostUsd, cost,
                inputAllowance, outputAllowance, inputUsdPerMillion, outputUsdPerMillion);
    }
}
