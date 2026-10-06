package com.example.llmgw.upstream;

import com.example.llmgw.protocol.Usage;

public interface StreamCallback {

    void onFirstToken();

    /**
     * Throwing an unchecked exception here aborts the upstream read and releases its connection,
     * which is how a client disconnect is propagated back to the vendor.
     */
    void onDelta(String text);

    void onCompleted(Usage usage, String finishReason);
}
