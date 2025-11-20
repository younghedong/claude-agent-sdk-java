package com.anthropic.claude.sdk.types.hooks;

/**
 * Supported hook events exposed by the CLI.
 */
public enum HookEvent {
    PRE_TOOL_USE("PreToolUse"),
    POST_TOOL_USE("PostToolUse"),
    USER_PROMPT_SUBMIT("UserPromptSubmit"),
    STOP("Stop"),
    SUBAGENT_STOP("SubagentStop"),
    PRE_COMPACT("PreCompact");

    private final String value;

    HookEvent(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
