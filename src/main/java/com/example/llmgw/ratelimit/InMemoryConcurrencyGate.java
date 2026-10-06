package com.example.llmgw.ratelimit;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

@Component
public class InMemoryConcurrencyGate implements ConcurrencyGate {

    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public InMemoryConcurrencyGate() {
        this(System::currentTimeMillis);
    }

    InMemoryConcurrencyGate(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public boolean tryAcquire(String key, String leaseId, int limit, Duration leaseTtl) {
        Slot slot = slots.computeIfAbsent(key, ignored -> new Slot());
        slot.lock.lock();
        try {
            long now = clock.getAsLong();
            reapExpired(slot);
            if (slot.leases.size() >= limit) {
                return false;
            }
            slot.leases.put(leaseId, now + leaseTtl.toMillis());
            return true;
        } finally {
            slot.lock.unlock();
        }
    }

    @Override
    public void release(String key, String leaseId) {
        Slot slot = slots.get(key);
        if (slot == null) {
            return;
        }
        slot.lock.lock();
        try {
            slot.leases.remove(leaseId);
        } finally {
            slot.lock.unlock();
        }
    }

    private void reapExpired(Slot slot) {
        long now = clock.getAsLong();
        Iterator<Map.Entry<String, Long>> iterator = slot.leases.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= now) {
                iterator.remove();
            }
        }
    }

    private static final class Slot {
        private final ReentrantLock lock = new ReentrantLock();
        private final Map<String, Long> leases = new HashMap<>();
    }
}
