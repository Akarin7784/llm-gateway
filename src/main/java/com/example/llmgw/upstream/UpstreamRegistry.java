package com.example.llmgw.upstream;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class UpstreamRegistry {

    private final Map<String, UpstreamAdapter> byVendor = new HashMap<>();

    public UpstreamRegistry(List<UpstreamAdapter> adapters) {
        for (UpstreamAdapter adapter : adapters) {
            byVendor.put(adapter.vendor(), adapter);
        }
    }

    public UpstreamAdapter require(String vendor) {
        UpstreamAdapter adapter = byVendor.get(vendor);
        if (adapter == null) {
            throw new IllegalStateException("no adapter registered for vendor '" + vendor + "'");
        }
        return adapter;
    }
}
