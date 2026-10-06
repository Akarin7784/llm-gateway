package com.example.llmgw.auth;

import com.example.llmgw.api.GatewayException;
import com.example.llmgw.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keys are held only as SHA-256 digests, mirroring the production rule that the plaintext key never
 * survives startup. Configuration carries plaintext for local development only.
 */
@Component
public class TenantRegistry {

    private final Map<String, Tenant> byKeyDigest = new LinkedHashMap<>();

    public TenantRegistry(GatewayProperties properties) {
        for (GatewayProperties.TenantConfig config : properties.getTenants()) {
            Tenant tenant = new Tenant(config.getId(), config.getName(), config.getRpm(),
                    config.getTpm(), config.getMaxConcurrency());
            byKeyDigest.put(digest(config.getApiKey()), tenant);
        }
    }

    public Tenant authenticate(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw GatewayException.unauthenticated("missing bearer token");
        }
        Tenant tenant = byKeyDigest.get(digest(apiKey));
        if (tenant == null) {
            throw GatewayException.unauthenticated("unknown api key");
        }
        return tenant;
    }

    private static String digest(String value) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
