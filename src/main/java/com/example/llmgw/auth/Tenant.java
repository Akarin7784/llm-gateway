package com.example.llmgw.auth;

public record Tenant(String id, String name, int rpm, long tpm, int maxConcurrency) {
}
