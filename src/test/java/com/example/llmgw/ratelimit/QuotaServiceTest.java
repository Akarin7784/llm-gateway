package com.example.llmgw.ratelimit;

import com.example.llmgw.api.GatewayException;
import com.example.llmgw.auth.Tenant;
import com.example.llmgw.config.GatewayProperties;
import com.example.llmgw.obs.GatewayMetrics;
import com.example.llmgw.protocol.ChatCompletionRequest;
import com.example.llmgw.protocol.ChatMessage;
import com.example.llmgw.protocol.Usage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotaServiceTest {

    private final InMemoryTokenBucketTest.AtomicClock clock = new InMemoryTokenBucketTest.AtomicClock(0);
    private final InMemoryTokenBucket bucket = new InMemoryTokenBucket(clock::asLong);
    private final InMemoryConcurrencyGate gate = new InMemoryConcurrencyGate(clock::asLong);
    private final GatewayProperties properties = new GatewayProperties();
    private final QuotaService quota = new QuotaService(bucket, gate, new TokenEstimator(properties),
            new GatewayMetrics(new SimpleMeterRegistry()), properties);

    private static Tenant tenant(int rpm, long tpm, int concurrency) {
        return new Tenant("t1", "Test", rpm, tpm, concurrency);
    }

    private static ChatCompletionRequest request(int maxTokens) {
        return new ChatCompletionRequest("gw-demo",
                List.of(new ChatMessage("user", "hello there this is a prompt")), false, maxTokens, null);
    }

    @Test
    void rejectsOnceTheRequestRateIsExhausted() {
        Tenant tenant = tenant(3, 1_000_000, 100);
        for (int i = 0; i < 3; i++) {
            quota.admit(tenant, request(100), "req-" + i, false);
        }
        assertThatThrownBy(() -> quota.admit(tenant, request(100), "req-4", false))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("requests-per-minute");
    }

    @Test
    void rejectedRequestReleasesTheConcurrencySlotItTook() {
        Tenant tenant = tenant(1, 1_000_000, 1);
        quota.release(quota.admit(tenant, request(10), "first", false));

        // rpm is spent, so this one is rejected after it already holds the only concurrency slot.
        assertThatThrownBy(() -> quota.admit(tenant, request(10), "second", false))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("requests-per-minute");

        clock.addMillis(61_000);
        // Had "second" leaked its slot, "third" would now be blocked by concurrency instead.
        assertThat(quota.admit(tenant, request(10), "third", false).enforced()).isTrue();
    }

    @Test
    void tokenCeilingBindsEvenBelowTheRequestRate() {
        Tenant tenant = tenant(1_000, 250, 100);
        quota.admit(tenant, request(100), "a", false);
        quota.admit(tenant, request(100), "b", false);
        assertThatThrownBy(() -> quota.admit(tenant, request(100), "c", false))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("tokens-per-minute");
    }

    @Test
    void settlementReturnsTheUnusedReservationForTheNextRequest() {
        // estimate = 7 prompt + 50 reserved completion = 57; a 120 window fits two, not three.
        Tenant tenant = tenant(1_000, 120, 100);
        QuotaReservation first = quota.admit(tenant, request(50), "a", false);
        quota.admit(tenant, request(50), "b", false);
        assertThatThrownBy(() -> quota.admit(tenant, request(50), "c", false))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("tokens-per-minute");

        // The vendor only produced 2 tokens, so 55 of the reservation go back and "c" fits.
        quota.settle(first, Usage.of(1, 1));
        assertThat(quota.admit(tenant, request(50), "c", false).enforced()).isTrue();
    }

    @Test
    void overUsageIsRecordedRatherThanRetroApproved() {
        Tenant tenant = tenant(1_000, 10_000, 100);
        QuotaReservation reservation = quota.admit(tenant, request(10), "a", false);
        double before = bucket.tryTake("tpm:" + tenant.id(), 0, BucketConfig.of(tenant.tpm(), tenant.tpm()))
                .remaining();
        quota.settle(reservation, Usage.of(10, 5_000));
        double after = bucket.tryTake("tpm:" + tenant.id(), 0, BucketConfig.of(tenant.tpm(), tenant.tpm()))
                .remaining();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void unreconciledSettlementKeepsTheReservationSpent() {
        Tenant tenant = tenant(1_000, 10_000, 100);
        QuotaReservation reservation = quota.admit(tenant, request(100), "a", false);
        double afterAdmit = bucket.tryTake("tpm:" + tenant.id(), 0, BucketConfig.of(tenant.tpm(), tenant.tpm()))
                .remaining();
        quota.settleUnreconciled(reservation);
        double afterSettle = bucket.tryTake("tpm:" + tenant.id(), 0, BucketConfig.of(tenant.tpm(), tenant.tpm()))
                .remaining();
        assertThat(afterSettle).isEqualTo(afterAdmit);
    }

    @Test
    void cacheHitsConsumeRequestRateButNoTokenBudget() {
        // tpm is only 10 tokens, so a real call of 57 would be refused; a cached one must not be.
        Tenant tenant = tenant(1_000, 10, 100);
        QuotaReservation hit = quota.admit(tenant, request(50), "cached", true);
        assertThat(hit.enforced()).isTrue();
        assertThat(hit.tokenReserved()).isFalse();

        // Settling a hit must not create phantom refunds or overage.
        quota.settle(hit, Usage.of(1, 1));
        assertThatThrownBy(() -> quota.admit(tenant, request(50), "miss", false))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("tokens-per-minute");
    }

    @Test
    void doubleSettlementIsIgnored() {
        Tenant tenant = tenant(1_000, 10_000, 100);
        QuotaReservation reservation = quota.admit(tenant, request(100), "a", false);
        quota.settle(reservation, Usage.of(1, 1));
        double afterFirst = bucket.tryTake("tpm:" + tenant.id(), 0, BucketConfig.of(tenant.tpm(), tenant.tpm()))
                .remaining();
        quota.settle(reservation, Usage.of(1, 1));
        assertThat(bucket.tryTake("tpm:" + tenant.id(), 0, BucketConfig.of(tenant.tpm(), tenant.tpm()))
                .remaining()).isEqualTo(afterFirst);
    }

    @Test
    void disabledQuotaAdmitsEverything() {
        properties.getQuota().setEnabled(false);
        Tenant tenant = tenant(1, 1, 1);
        quota.admit(tenant, request(10), "a", false);
        quota.admit(tenant, request(10), "b", false);
        quota.admit(tenant, request(10), "c", false);
    }
}
