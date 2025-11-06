package com.anthropic.claude.sdk.hooks;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nullable;

/**
 * Specific output for PostToolUse hook event.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PostToolUseHookSpecificOutput {

    @JsonProperty("hookEventName")
    private final String hookEventName = "PostToolUse";

    @JsonProperty("additionalContext")
    @Nullable
    private final String additionalContext;

    private PostToolUseHookSpecificOutput(Builder builder) {
        this.additionalContext = builder.additionalContext;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getHookEventName() {
        return hookEventName;
    }

    @Nullable
    public String getAdditionalContext() {
        return additionalContext;
    }

    public static class Builder {
        private String additionalContext;

        /**
         * Set additional context to add to the conversation.
         *
         * @param additionalContext the additional context
         * @return this builder
         */
        public Builder additionalContext(String additionalContext) {
            this.additionalContext = additionalContext;
            return this;
        }

        public PostToolUseHookSpecificOutput build() {
            return new PostToolUseHookSpecificOutput(this);
        }
    }
}
