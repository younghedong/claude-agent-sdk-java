package com.anthropic.claude.sdk.types.messages;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

/**
 * Stream event emitted while Claude is still composing a response.
 */
@Data
@AllArgsConstructor
public final class StreamEvent implements Message {
    @JsonProperty("uuid")
    private final String uuid;

    @JsonProperty("session_id")
    private final String sessionId;

    @JsonProperty("event")
    private final Map<String, Object> event;

    @JsonProperty("parent_tool_use_id")
    private final String parentToolUseId;

    @Override
    public String getType() {
        return "stream_event";
    }
}
