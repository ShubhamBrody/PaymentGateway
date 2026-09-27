package com.paymentsystem.bank;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentsystem.models.BankScenario;
import com.paymentsystem.models.PaymentAttemptStatus;
import java.time.Duration;
import java.util.EnumSet;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class BankBehaviorTests {

    private static final Duration APPROVE_DELAY = Duration.ofMillis(300);
    private static final Duration SLOW_DELAY = Duration.ofSeconds(3);

    @ParameterizedTest
    @CsvSource({
            "0.0, APPROVE", "0.7999, APPROVE",
            "0.80, DECLINE", "0.9199, DECLINE",
            "0.92, SLOW_APPROVE", "0.9699, SLOW_APPROVE",
            "0.97, TIMEOUT_THEN_APPROVE", "0.9899, TIMEOUT_THEN_APPROVE",
            "0.99, DUPLICATE_REPLY", "0.9999, DUPLICATE_REPLY"
    })
    void randomScenarioFollowsTheConfiguredWeights(double roll, BankScenario expected) {
        assertThat(BankBehavior.randomScenario(roll)).isEqualTo(expected);
    }

    @Test
    void randomIsResolvedToAConcreteScenarioOnce() {
        RandomGenerator alwaysZero = () -> 0L;

        BankBehavior.Decision decision = BankBehavior.decide(BankScenario.RANDOM, alwaysZero);

        assertThat(decision.scenario()).isEqualTo(BankScenario.APPROVE);
        assertThat(decision.outcome()).isEqualTo(BankOutcome.APPROVED);
    }

    @Test
    void declineScenarioIsDeclinedWithAReason() {
        BankBehavior.Decision decision = BankBehavior.decide(BankScenario.DECLINE, () -> 0L);

        assertThat(decision.outcome()).isEqualTo(BankOutcome.DECLINED);
        assertThat(decision.declineReason()).isEqualTo("insufficient_funds");
    }

    @ParameterizedTest
    @EnumSource(value = BankScenario.class, names = {"APPROVE", "SLOW_APPROVE", "TIMEOUT_THEN_APPROVE", "DUPLICATE_REPLY"})
    void approvingScenariosApprove(BankScenario scenario) {
        assertThat(BankBehavior.decide(scenario, () -> 0L).outcome()).isEqualTo(BankOutcome.APPROVED);
    }

    @ParameterizedTest
    @CsvSource({
            "APPROVE, 1, 1, 300",
            "DECLINE, 1, 1, 300",
            "SLOW_APPROVE, 1, 1, 3000",
            "DUPLICATE_REPLY, 1, 2, 300",
            "TIMEOUT_THEN_APPROVE, 1, 0, 300",
            "TIMEOUT_THEN_APPROVE, 2, 1, 300",
            "NO_REPLY, 1, 0, 0",
            "NO_REPLY, 3, 0, 0"
    })
    void replyPlanDependsOnScenarioAndDelivery(
            BankScenario scenario, long delivery, int expectedReplies, long expectedDelayMillis) {
        BankBehavior.Decision decision = BankBehavior.decide(scenario, () -> 0L);

        BankBehavior.ReplyPlan plan = BankBehavior.plan(decision, delivery, APPROVE_DELAY, SLOW_DELAY);

        assertThat(plan.replies()).isEqualTo(expectedReplies);
        assertThat(plan.delay()).isEqualTo(Duration.ofMillis(expectedDelayMillis));
    }

    @Test
    void onlySucceededDeclinedAndFailedAreFinal() {
        assertThat(EnumSet.allOf(PaymentAttemptStatus.class))
                .filteredOn(PaymentAttemptStatus::isFinal)
                .containsExactlyInAnyOrder(
                        PaymentAttemptStatus.SUCCEEDED, PaymentAttemptStatus.DECLINED, PaymentAttemptStatus.FAILED);
    }
}
