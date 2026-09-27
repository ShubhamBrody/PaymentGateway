package com.paymentsystem.bank;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentsystem.config.BankSimulatorProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class BankDirectoryTests {

    private final BankDirectory directory = new BankDirectory(
            new BankSimulatorProperties(true, 30, Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofHours(24)));

    @Test
    void thereAreThirtyBanksWithStableCodes() {
        List<BankDirectory.Bank> banks = directory.all();

        assertThat(banks).hasSize(30);
        assertThat(banks.getFirst().code()).isEqualTo("BANK01");
        assertThat(banks.getLast().code()).isEqualTo("BANK30");
        assertThat(banks).extracting(BankDirectory.Bank::code).doesNotHaveDuplicates();
        assertThat(directory.defaultBank().code()).isEqualTo("BANK01");
    }

    @Test
    void eachBankHasItsOwnSpeedAndDeclineRateWithinTheDocumentedRange() {
        List<BankDirectory.Bank> banks = directory.all();

        assertThat(banks).allSatisfy(bank -> {
            assertThat(bank.speedFactor()).isBetween(0.5, 1.5);
            assertThat(bank.declineRate()).isBetween(0.06, 0.18);
        });
        assertThat(banks).extracting(BankDirectory.Bank::speedFactor).doesNotHaveDuplicates();
        assertThat(banks).extracting(BankDirectory.Bank::declineRate).doesNotHaveDuplicates();
    }

    @Test
    void delaysAreScaledBySpeed() {
        BankDirectory.Bank bank = new BankDirectory.Bank("BANK99", "Test", 1.5, 0.1);

        assertThat(bank.scaled(Duration.ofMillis(300))).isEqualTo(Duration.ofMillis(450));
    }

    @Test
    void unknownOrMissingCodesAreNotFound() {
        assertThat(directory.find("BANK07")).map(BankDirectory.Bank::code).contains("BANK07");
        assertThat(directory.find("BANK31")).isEmpty();
        assertThat(directory.find(null)).isEmpty();
    }
}
