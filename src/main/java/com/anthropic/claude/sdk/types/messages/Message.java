package com.anthropic.claude.sdk.types.messages;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base interface for all message types.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = UserMessage.class, name = "user"),
    @JsonSubTypes.Type(value = AssistantMessage.class, name = "assistant"),
    @JsonSubTypes.Type(value = SystemMessage.class, name = "system"),
    @JsonSubTypes.Type(value = ResultMessage.class, name = "result"),
    @JsonSubTypes.Type(value = StreamEvent.class, name = "stream_event")
})
public interface Message {

    /**
     * Get the type of this message.
     */
    String getType();
}
