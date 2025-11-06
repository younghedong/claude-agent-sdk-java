package com.anthropic.claude.sdk.hooks;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * Specific output for PreToolUse hook event.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PreToolUseHookSpecificOutput {

    @JsonProperty("hookEventName")
    private final String hookEventName = "PreToolUse";

    @JsonProperty("permissionDecision")
    @Nullable
    private final String permissionDecision;

    @JsonProperty("permissionDecisionReason")
    @Nullable
    private final String permissionDecisionReason;

    @JsonProperty("updatedInput")
    @Nullable
    private final Map<String, Object> updatedInput;

    private PreToolUseHookSpecificOutput(Builder builder) {
        this.permissionDecision = builder.permissionDecision;
        this.permissionDecisionReason = builder.permissionDecisionReason;
        this.updatedInput = builder.updatedInput;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getHookEventName() {
        return hookEventName;
    }

    @Nullable
    public String getPermissionDecision() {
        return permissionDecision;
    }

    @Nullable
    public String getPermissionDecisionReason() {
        return permissionDecisionReason;
    }

    @Nullable
    public Map<String, Object> getUpdatedInput() {
        return updatedInput;
    }

    public static class Builder {
        private String permissionDecision;
        private String permissionDecisionReason;
        private Map<String, Object> updatedInput;

        /**
         * Set permission decision (allow, deny, ask).
         *
         * @param permissionDecision the permission decision
         * @return this builder
         */
        public Builder permissionDecision(String permissionDecision) {
            this.permissionDecision = permissionDecision;
            return this;
        }

        /**
         * Set reason for the permission decision.
         *
         * @param permissionDecisionReason the reason
         * @return this builder
         */
        public Builder permissionDecisionReason(String permissionDecisionReason) {
            this.permissionDecisionReason = permissionDecisionReason;
            return this;
        }

        /**
         * Set modified tool input.
         *
         * @param updatedInput the updated input
         * @return this builder
         */
        public Builder updatedInput(Map<String, Object> updatedInput) {
            this.updatedInput = updatedInput;
            return this;
        }

        public PreToolUseHookSpecificOutput build() {
            return new PreToolUseHookSpecificOutput(this);
        }
    }
}
