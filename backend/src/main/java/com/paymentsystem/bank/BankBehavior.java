package com.paymentsystem.bank;

import com.paymentsystem.models.BankScenario;
import java.time.Duration;
import java.util.random.RandomGenerator;

public final class BankBehavior {

    public record Decision(BankScenario scenario, BankOutcome outcome, String declineReason) {
    }

    public record ReplyPlan(int replies, Duration delay) {
    }

    private BankBehavior() {
    }

    public static Decision decide(BankScenario requested, double declineRate, RandomGenerator random) {
        BankScenario scenario = requested == BankScenario.RANDOM
                ? randomScenario(random.nextDouble(), declineRate)
                : requested;
        return scenario == BankScenario.DECLINE
                ? new Decision(scenario, BankOutcome.DECLINED, "insufficient_funds")
                : new Decision(scenario, BankOutcome.APPROVED, null);
    }

    // deliveryNumber counts how many times this bank has received the same request, starting at 1.
    public static ReplyPlan plan(Decision decision, long deliveryNumber, Duration approveDelay, Duration slowDelay) {
        return switch (decision.scenario()) {
            case NO_REPLY -> new ReplyPlan(0, Duration.ZERO);
            case TIMEOUT_THEN_APPROVE -> new ReplyPlan(deliveryNumber == 1 ? 0 : 1, approveDelay);
            case DUPLICATE_REPLY -> new ReplyPlan(2, approveDelay);
            case SLOW_APPROVE -> new ReplyPlan(1, slowDelay);
            case APPROVE, DECLINE -> new ReplyPlan(1, approveDelay);
            case RANDOM -> throw new IllegalStateException("RANDOM must be resolved by decide()");
        };
    }

    // The bank's decline rate splits the first 92%; slow, lost, and duplicate replies stay at 5%, 2%, and 1%.
    static BankScenario randomScenario(double roll, double declineRate) {
        if (roll < 0.92 - declineRate) {
            return BankScenario.APPROVE;
        }
        if (roll < 0.92) {
            return BankScenario.DECLINE;
        }
        if (roll < 0.97) {
            return BankScenario.SLOW_APPROVE;
        }
        if (roll < 0.99) {
            return BankScenario.TIMEOUT_THEN_APPROVE;
        }
        return BankScenario.DUPLICATE_REPLY;
    }
}
