package com.example.llmgw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private Duration emitterTimeout = Duration.ofSeconds(120);
    private Duration streamHeartbeatInterval = Duration.ofSeconds(2);
    private Quota quota = new Quota();
    private Cache cache = new Cache();
    private Routing routing = new Routing();
    private Billing billing = new Billing();
    private List<UpstreamConfig> upstreams = new ArrayList<>();
    private List<TenantConfig> tenants = new ArrayList<>();

    public Billing getBilling() {
        return billing;
    }

    public void setBilling(Billing billing) {
        this.billing = billing;
    }

    public Routing getRouting() {
        return routing;
    }

    public void setRouting(Routing routing) {
        this.routing = routing;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }

    public Quota getQuota() {
        return quota;
    }

    public void setQuota(Quota quota) {
        this.quota = quota;
    }

    public Duration getEmitterTimeout() {
        return emitterTimeout;
    }

    public void setEmitterTimeout(Duration emitterTimeout) {
        this.emitterTimeout = emitterTimeout;
    }

    public Duration getStreamHeartbeatInterval() {
        return streamHeartbeatInterval;
    }

    public void setStreamHeartbeatInterval(Duration streamHeartbeatInterval) {
        this.streamHeartbeatInterval = streamHeartbeatInterval;
    }

    public List<UpstreamConfig> getUpstreams() {
        return upstreams;
    }

    public void setUpstreams(List<UpstreamConfig> upstreams) {
        this.upstreams = upstreams;
    }

    public List<TenantConfig> getTenants() {
        return tenants;
    }

    public void setTenants(List<TenantConfig> tenants) {
        this.tenants = tenants;
    }

    /** Declaration order in configuration is the degradation order at runtime. */
    public static class UpstreamConfig {
        private String name;
        private String vendor = "openai-compatible";
        private String baseUrl;
        private String apiKey;
        private List<String> models = new ArrayList<>();
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration firstTokenTimeout = Duration.ofSeconds(15);
        private Duration readTimeout = Duration.ofSeconds(60);
        private double priceInPer1k;
        private double priceOutPer1k;

        public double getPriceInPer1k() {
            return priceInPer1k;
        }

        public void setPriceInPer1k(double priceInPer1k) {
            this.priceInPer1k = priceInPer1k;
        }

        public double getPriceOutPer1k() {
            return priceOutPer1k;
        }

        public void setPriceOutPer1k(double priceOutPer1k) {
            this.priceOutPer1k = priceOutPer1k;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getVendor() {
            return vendor;
        }

        public void setVendor(String vendor) {
            this.vendor = vendor;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public List<String> getModels() {
            return models;
        }

        public void setModels(List<String> models) {
            this.models = models;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getFirstTokenTimeout() {
            return firstTokenTimeout;
        }

        public void setFirstTokenTimeout(Duration firstTokenTimeout) {
            this.firstTokenTimeout = firstTokenTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    public static class TenantConfig {
        private String id;
        private String name;
        private String apiKey;
        private int rpm = 60;
        private long tpm = 60_000;
        private int maxConcurrency = 32;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public int getRpm() {
            return rpm;
        }

        public void setRpm(int rpm) {
            this.rpm = rpm;
        }

        public long getTpm() {
            return tpm;
        }

        public void setTpm(long tpm) {
            this.tpm = tpm;
        }

        public int getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }
    }

    public static class Quota {
        private boolean enabled = true;
        private boolean distributed = false;
        private int defaultCompletionTokens = 512;
        private Duration leaseTtl = Duration.ofMinutes(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isDistributed() {
            return distributed;
        }

        public void setDistributed(boolean distributed) {
            this.distributed = distributed;
        }

        public int getDefaultCompletionTokens() {
            return defaultCompletionTokens;
        }

        public void setDefaultCompletionTokens(int defaultCompletionTokens) {
            this.defaultCompletionTokens = defaultCompletionTokens;
        }

        public Duration getLeaseTtl() {
            return leaseTtl;
        }

        public void setLeaseTtl(Duration leaseTtl) {
            this.leaseTtl = leaseTtl;
        }
    }

    public static class Cache {
        private boolean enabled = true;
        private long maxEntries = 10_000;
        private Duration ttl = Duration.ofMinutes(10);
        /**
         * When true the key is scoped per tenant, so one team's prompt never serves another team's
         * cached answer. Sharing saves tokens; not sharing is what makes the cache safe to turn on at
         * all for text that may contain private context.
         */
        private boolean tenantIsolation = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(long maxEntries) {
            this.maxEntries = maxEntries;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public boolean isTenantIsolation() {
            return tenantIsolation;
        }

        public void setTenantIsolation(boolean tenantIsolation) {
            this.tenantIsolation = tenantIsolation;
        }
    }

    public static class Routing {
        private int windowSeconds = 10;
        private double errorThreshold = 0.5;
        private int minVolume = 8;
        private int consecutiveFailureThreshold = 3;
        private Duration openDuration = Duration.ofSeconds(10);
        private int halfOpenProbes = 2;
        private double latencyWeight = 1.0;
        private double errorWeight = 2.0;
        private double costWeight = 0.5;
        private long assumedPromptTokens = 500;
        private long assumedCompletionTokens = 500;

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public double getErrorThreshold() {
            return errorThreshold;
        }

        public void setErrorThreshold(double errorThreshold) {
            this.errorThreshold = errorThreshold;
        }

        public int getMinVolume() {
            return minVolume;
        }

        public void setMinVolume(int minVolume) {
            this.minVolume = minVolume;
        }

        public int getConsecutiveFailureThreshold() {
            return consecutiveFailureThreshold;
        }

        public void setConsecutiveFailureThreshold(int consecutiveFailureThreshold) {
            this.consecutiveFailureThreshold = consecutiveFailureThreshold;
        }

        public Duration getOpenDuration() {
            return openDuration;
        }

        public void setOpenDuration(Duration openDuration) {
            this.openDuration = openDuration;
        }

        public int getHalfOpenProbes() {
            return halfOpenProbes;
        }

        public void setHalfOpenProbes(int halfOpenProbes) {
            this.halfOpenProbes = halfOpenProbes;
        }

        public double getLatencyWeight() {
            return latencyWeight;
        }

        public void setLatencyWeight(double latencyWeight) {
            this.latencyWeight = latencyWeight;
        }

        public double getErrorWeight() {
            return errorWeight;
        }

        public void setErrorWeight(double errorWeight) {
            this.errorWeight = errorWeight;
        }

        public double getCostWeight() {
            return costWeight;
        }

        public void setCostWeight(double costWeight) {
            this.costWeight = costWeight;
        }

        public long getAssumedPromptTokens() {
            return assumedPromptTokens;
        }

        public void setAssumedPromptTokens(long assumedPromptTokens) {
            this.assumedPromptTokens = assumedPromptTokens;
        }

        public long getAssumedCompletionTokens() {
            return assumedCompletionTokens;
        }

        public void setAssumedCompletionTokens(long assumedCompletionTokens) {
            this.assumedCompletionTokens = assumedCompletionTokens;
        }
    }

    public static class Billing {
        private boolean enabled = true;
        private int queueCapacity = 10_000;
        private int batchSize = 200;
        private Duration flushInterval = Duration.ofMillis(250);
        private Duration shutdownDrainTimeout = Duration.ofSeconds(5);
        private String spillFile = "data/ledger-spill.jsonl";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getFlushInterval() {
            return flushInterval;
        }

        public void setFlushInterval(Duration flushInterval) {
            this.flushInterval = flushInterval;
        }

        public Duration getShutdownDrainTimeout() {
            return shutdownDrainTimeout;
        }

        public void setShutdownDrainTimeout(Duration shutdownDrainTimeout) {
            this.shutdownDrainTimeout = shutdownDrainTimeout;
        }

        public String getSpillFile() {
            return spillFile;
        }

        public void setSpillFile(String spillFile) {
            this.spillFile = spillFile;
        }
    }
}
