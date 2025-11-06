package com.anthropic.claude.sdk.types;

import com.anthropic.claude.sdk.hooks.HookMatcher;
import com.anthropic.claude.sdk.mcp.McpServerConfig;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Configuration options for Claude Agent.
 *
 * Builder pattern is used to construct instances.
 */
public class ClaudeAgentOptions {

    @Nullable
    private final List<String> allowedTools;
    @Nullable
    private final String systemPrompt;
    @Nullable
    private final Map<String, McpServerConfig> mcpServers;
    @Nullable
    private final PermissionMode permissionMode;
    @Nullable
    private final Integer maxTurns;
    @Nullable
    private final Double maxBudgetUsd;
    @Nullable
    private final BiFunction<ToolUseBlock, ToolPermissionContext, PermissionResult> canUseTool;
    @Nullable
    private final Map<String, List<HookMatcher>> hooks;
    @Nullable
    private final Path cwd;
    @Nullable
    private final String model;
    @Nullable
    private final Integer maxThinkingTokens;
    // New fields to match Python SDK
    @Nullable
    private final Path cliPath;
    @Nullable
    private final Map<String, AgentDefinition> agents;
    @Nullable
    private final List<String> disallowedTools;
    private final boolean continueConversation;
    @Nullable
    private final String resume;
    @Nullable
    private final Map<String, String> env;
    @Nullable
    private final List<Path> addDirs;
    @Nullable
    private final String user;
    private final boolean includePartialMessages;
    private final boolean forkSession;

    private ClaudeAgentOptions(Builder builder) {
        this.allowedTools = builder.allowedTools;
        this.systemPrompt = builder.systemPrompt;
        this.mcpServers = builder.mcpServers;
        this.permissionMode = builder.permissionMode;
        this.maxTurns = builder.maxTurns;
        this.maxBudgetUsd = builder.maxBudgetUsd;
        this.canUseTool = builder.canUseTool;
        this.hooks = builder.hooks;
        this.cwd = builder.cwd;
        this.model = builder.model;
        this.maxThinkingTokens = builder.maxThinkingTokens;
        // New fields
        this.cliPath = builder.cliPath;
        this.agents = builder.agents;
        this.disallowedTools = builder.disallowedTools;
        this.continueConversation = builder.continueConversation;
        this.resume = builder.resume;
        this.env = builder.env;
        this.addDirs = builder.addDirs;
        this.user = builder.user;
        this.includePartialMessages = builder.includePartialMessages;
        this.forkSession = builder.forkSession;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Nullable
    public List<String> getAllowedTools() {
        return allowedTools;
    }

    @Nullable
    public String getSystemPrompt() {
        return systemPrompt;
    }

    @Nullable
    public Map<String, McpServerConfig> getMcpServers() {
        return mcpServers;
    }

    @Nullable
    public PermissionMode getPermissionMode() {
        return permissionMode;
    }

    @Nullable
    public Integer getMaxTurns() {
        return maxTurns;
    }

    @Nullable
    public Double getMaxBudgetUsd() {
        return maxBudgetUsd;
    }

    @Nullable
    public BiFunction<ToolUseBlock, ToolPermissionContext, PermissionResult> getCanUseTool() {
        return canUseTool;
    }

    @Nullable
    public Map<String, List<HookMatcher>> getHooks() {
        return hooks;
    }

    @Nullable
    public Path getCwd() {
        return cwd;
    }

    @Nullable
    public String getModel() {
        return model;
    }

    @Nullable
    public Integer getMaxThinkingTokens() {
        return maxThinkingTokens;
    }

    @Nullable
    public Path getCliPath() {
        return cliPath;
    }

    @Nullable
    public Map<String, AgentDefinition> getAgents() {
        return agents;
    }

    @Nullable
    public List<String> getDisallowedTools() {
        return disallowedTools;
    }

    public boolean isContinueConversation() {
        return continueConversation;
    }

    @Nullable
    public String getResume() {
        return resume;
    }

    @Nullable
    public Map<String, String> getEnv() {
        return env;
    }

    @Nullable
    public List<Path> getAddDirs() {
        return addDirs;
    }

    @Nullable
    public String getUser() {
        return user;
    }

    public boolean isIncludePartialMessages() {
        return includePartialMessages;
    }

    public boolean isForkSession() {
        return forkSession;
    }

    public static class Builder {
        private List<String> allowedTools;
        private String systemPrompt;
        private Map<String, McpServerConfig> mcpServers;
        private PermissionMode permissionMode;
        private Integer maxTurns;
        private Double maxBudgetUsd;
        private BiFunction<ToolUseBlock, ToolPermissionContext, PermissionResult> canUseTool;
        private Map<String, List<HookMatcher>> hooks;
        private Path cwd;
        private String model;
        private Integer maxThinkingTokens;
        // New fields
        private Path cliPath;
        private Map<String, AgentDefinition> agents;
        private List<String> disallowedTools;
        private boolean continueConversation = false;
        private String resume;
        private Map<String, String> env;
        private List<Path> addDirs;
        private String user;
        private boolean includePartialMessages = false;
        private boolean forkSession = false;

        private Builder() {
        }

        public Builder allowedTools(List<String> allowedTools) {
            this.allowedTools = allowedTools;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder mcpServers(Map<String, McpServerConfig> mcpServers) {
            this.mcpServers = mcpServers;
            return this;
        }

        public Builder permissionMode(PermissionMode permissionMode) {
            this.permissionMode = permissionMode;
            return this;
        }

        public Builder maxTurns(Integer maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        public Builder maxBudgetUsd(Double maxBudgetUsd) {
            this.maxBudgetUsd = maxBudgetUsd;
            return this;
        }

        public Builder canUseTool(BiFunction<ToolUseBlock, ToolPermissionContext, PermissionResult> canUseTool) {
            this.canUseTool = canUseTool;
            return this;
        }

        public Builder hooks(Map<String, List<HookMatcher>> hooks) {
            this.hooks = hooks;
            return this;
        }

        public Builder cwd(Path cwd) {
            this.cwd = cwd;
            return this;
        }

        public Builder cwd(String cwd) {
            this.cwd = Path.of(cwd);
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder maxThinkingTokens(Integer maxThinkingTokens) {
            this.maxThinkingTokens = maxThinkingTokens;
            return this;
        }

        /**
         * Set the path to the Claude CLI executable.
         *
         * @param cliPath the CLI path
         * @return this builder
         */
        public Builder cliPath(Path cliPath) {
            this.cliPath = cliPath;
            return this;
        }

        /**
         * Set the path to the Claude CLI executable.
         *
         * @param cliPath the CLI path as a string
         * @return this builder
         */
        public Builder cliPath(String cliPath) {
            this.cliPath = Path.of(cliPath);
            return this;
        }

        /**
         * Set agent definitions.
         *
         * @param agents map of agent name to definition
         * @return this builder
         */
        public Builder agents(Map<String, AgentDefinition> agents) {
            this.agents = agents;
            return this;
        }

        /**
         * Set tools that should not be allowed.
         *
         * @param disallowedTools list of disallowed tool names
         * @return this builder
         */
        public Builder disallowedTools(List<String> disallowedTools) {
            this.disallowedTools = disallowedTools;
            return this;
        }

        /**
         * Set whether to continue an existing conversation.
         *
         * @param continueConversation true to continue conversation
         * @return this builder
         */
        public Builder continueConversation(boolean continueConversation) {
            this.continueConversation = continueConversation;
            return this;
        }

        /**
         * Set session ID to resume.
         *
         * @param resume session ID to resume
         * @return this builder
         */
        public Builder resume(String resume) {
            this.resume = resume;
            return this;
        }

        /**
         * Set environment variables for the CLI process.
         *
         * @param env environment variables
         * @return this builder
         */
        public Builder env(Map<String, String> env) {
            this.env = env;
            return this;
        }

        /**
         * Set additional directories to include.
         *
         * @param addDirs list of directories
         * @return this builder
         */
        public Builder addDirs(List<Path> addDirs) {
            this.addDirs = addDirs;
            return this;
        }

        /**
         * Set user identifier.
         *
         * @param user user identifier
         * @return this builder
         */
        public Builder user(String user) {
            this.user = user;
            return this;
        }

        /**
         * Set whether to include partial messages.
         *
         * @param includePartialMessages true to include partial messages
         * @return this builder
         */
        public Builder includePartialMessages(boolean includePartialMessages) {
            this.includePartialMessages = includePartialMessages;
            return this;
        }

        /**
         * Set whether to fork the session.
         *
         * @param forkSession true to fork session
         * @return this builder
         */
        public Builder forkSession(boolean forkSession) {
            this.forkSession = forkSession;
            return this;
        }

        public ClaudeAgentOptions build() {
            return new ClaudeAgentOptions(this);
        }
    }
}
