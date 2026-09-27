package com.paymentsystem.bank;

import com.paymentsystem.config.BankSimulatorProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

// The banks customers can pay from, BANK01 upwards. A bank's profile comes from its number, so it behaves the same
// in every run: it answers in 0.5x to 1.5x the configured delays and declines 6% to 18% of RANDOM requests.
@Component
public class BankDirectory {

    public record Bank(String code, String name, double speedFactor, double declineRate) {

        public Duration scaled(Duration delay) {
            return Duration.ofMillis(Math.round(delay.toMillis() * speedFactor));
        }
    }

    private final Map<String, Bank> banks = new LinkedHashMap<>();

    public BankDirectory(BankSimulatorProperties properties) {
        int count = properties.banks();
        for (int number = 1; number <= count; number++) {
            // Multiplying by 7 and 11 keeps neighbouring banks from getting similar profiles.
            double speedStep = count == 1 ? 0.5 : (number * 7 % count) / (double) (count - 1);
            double declineStep = count == 1 ? 0.5 : (number * 11 % count) / (double) (count - 1);
            String code = "BANK%02d".formatted(number);
            banks.put(code, new Bank(
                    code, "Simulated Bank %02d".formatted(number), 0.5 + speedStep, 0.06 + 0.12 * declineStep));
        }
    }

    public List<Bank> all() {
        return List.copyOf(banks.values());
    }

    public Optional<Bank> find(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(banks.get(code));
    }

    public Bank defaultBank() {
        return banks.values().iterator().next();
    }
}
