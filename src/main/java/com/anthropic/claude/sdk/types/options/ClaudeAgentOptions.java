package com.anthropic.claude.sdk.types.options;

import com.anthropic.claude.sdk.types.hooks.HookEvent;
import com.anthropic.claude.sdk.types.hooks.HookMatcher;
import com.anthropic.claude.sdk.types.permissions.ToolPermissionCallback;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Configuration options for Claude Agent SDK.
 * Use {@link #builder()} to create instances.
 */
@Getter
@Builder(toBuilder = true)
public class ClaudeAgentOptions {

    private final String systemPrompt;

    @Singular
    private final List<String> allowedTools;

    @Singular
    private final List<String> disallowedTools;

    private final Integer maxTurns;
    private final Double maxBudgetUsd;
    private final String model;
    private final String fallbackModel;
    private final PermissionMode permissionMode;
    private final String permissionPromptToolName;
    private final ToolPermissionCallback canUseTool;

    @Singular("hook")
    private final Map<String, List<HookMatcher>> hooks;

    @Singular("hookEvent")
    private final Map<HookEvent, List<HookMatcher>> typedHooks;

    private final Path cliPath;
    private final Path cwd;

    @Singular("mcpServer")
    private final Map<String, Object> mcpServers;

    @Singular("envVar")
    private final Map<String, String> env;

    @Singular
    private final List<Path> addDirs;

    @Builder.Default
    private final boolean continueConversation = false;

    private final String resume;
    private final String settings;

    @Singular
    private final List<SettingSource> settingSources;

    private final Integer maxThinkingTokens;

    @Singular("extraArg")
    private final Map<String, String> extraArgs;

    @Builder.Default
    private final boolean includePartialMessages = false;

    @Builder.Default
    private final boolean forkSession = false;

    private final String user;
    private final Integer maxBufferSize;

    @Singular("agent")
    private final Map<String, AgentDefinition> agents;

    @Singular
    private final List<SdkPluginConfig> plugins;

    private final Map<String, Object> outputFormat;

    private final Consumer<String> stderr;

    /**
     * Merge typed and untyped hooks for transport consumption.
     */
    public Map<String, List<HookMatcher>> resolvedHooks() {
        Map<String, List<HookMatcher>> resolved = new java.util.HashMap<>();
        if (hooks != null) {
            hooks.forEach((key, value) -> resolved.put(key, value));
        }
        if (typedHooks != null) {
            typedHooks.forEach((event, matchers) -> {
                String key = event.getValue();
                resolved.merge(key, matchers, (existing, incoming) -> {
                    List<HookMatcher> merged = new java.util.ArrayList<>(existing);
                    merged.addAll(incoming);
                    return merged;
                });
            });
        }
        return resolved;
    }
}
