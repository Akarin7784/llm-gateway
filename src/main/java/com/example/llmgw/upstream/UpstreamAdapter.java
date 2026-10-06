package com.example.llmgw.upstream;

import com.example.llmgw.protocol.UpstreamResult;
import com.fasterxml.jackson.databind.JsonNode;

public interface UpstreamAdapter {

    String vendor();

    UpstreamResult complete(UpstreamTarget target, JsonNode payload) throws UpstreamException;

    void stream(UpstreamTarget target, JsonNode payload, StreamCallback callback,
                StreamAbortHandle abortHandle) throws UpstreamException;
}
