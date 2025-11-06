package com.anthropic.claude.sdk.hooks;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nullable;

/**
 * Context information passed to hook callbacks.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HookContext {

    @JsonProperty("signal")
    @Nullable
    private final Object signal;

    public HookContext(@Nullable Object signal) {
        this.signal = signal;
    }

    public HookContext() {
        this(null);
    }

    @Nullable
    public Object getSignal() {
        return signal;
    }
}
