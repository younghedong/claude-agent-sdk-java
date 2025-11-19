package com.anthropic.claude.sdk.examples;

import com.anthropic.claude.sdk.client.ClaudeSDKClient;
import com.anthropic.claude.sdk.types.content.TextBlock;
import com.anthropic.claude.sdk.types.hooks.Hook;
import com.anthropic.claude.sdk.types.hooks.HookContext;
import com.anthropic.claude.sdk.types.hooks.HookMatcher;
import com.anthropic.claude.sdk.types.messages.AssistantMessage;
import com.anthropic.claude.sdk.types.messages.Message;
import com.anthropic.claude.sdk.types.messages.ResultMessage;
import com.anthropic.claude.sdk.types.options.ClaudeAgentOptions;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Hooks Example - Demonstrates using hooks with Claude Agent SDK.
 *
 * This example shows various hook patterns:
 * - PreToolUse: Block dangerous commands
 * - PostToolUse: Review tool output
 * - UserPromptSubmit: Add custom context
 *
 * Usage:
 *   java HooksExample
 */
@Slf4j
public class HooksExample {

    public static void main(String[] args) {
        log.info("=".repeat(60));
        log.info("Claude Agent SDK - Hooks Examples");
        log.info("=".repeat(60));

        try {
            preToolUseExample();
            postToolUseExample();
            strictApprovalExample();
        } catch (Exception e) {
            log.error("Error: {}", e.getMessage(), e);
        }
    }

    /**
     * Example 1: PreToolUse Hook - Block dangerous Bash commands
     */
    static void preToolUseExample() {
        log.info("=== PreToolUse Example ===");
        log.info("This example blocks specific bash commands using PreToolUse hook.");

        // Define hook to check bash commands
        Hook checkBashCommand = (input, toolUseId, context) -> {
            String toolName = (String) input.get("tool_name");
            @SuppressWarnings("unchecked")
            Map<String, Object> toolInput = (Map<String, Object>) input.get("tool_input");

            if (!"Bash".equals(toolName)) {
                return CompletableFuture.completedFuture(new HashMap<>());
            }

            String command = (String) toolInput.get("command");
            String[] blockPatterns = {"foo.sh", "rm -rf", "sudo"};

            for (String pattern : blockPatterns) {
                if (command != null && command.contains(pattern)) {
                    log.warn("⚠️  Blocked command: {}", command);

                    Map<String, Object> result = new HashMap<>();
                    Map<String, Object> hookOutput = new HashMap<>();
                    hookOutput.put("hookEventName", "PreToolUse");
                    hookOutput.put("permissionDecision", "deny");
                    hookOutput.put("permissionDecisionReason",
                        "Command contains forbidden pattern: " + pattern);
                    result.put("hookSpecificOutput", hookOutput);

                    return CompletableFuture.completedFuture(result);
                }
            }

            return CompletableFuture.completedFuture(new HashMap<>());
        };

        // Configure options with hook
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
            .allowedTool("Bash")
            .hooks(Map.of(
                "PreToolUse", List.of(
                    new HookMatcher("Bash", List.of(checkBashCommand))
                )
            ))
            .build();

        try (ClaudeSDKClient client = new ClaudeSDKClient(options)) {
            client.connect().join();

            // Test 1: Blocked command
            log.info("Test 1: Trying a blocked command (./foo.sh)...");
            client.query("Run the bash command: ./foo.sh --help").join();
            consumeUntilResult(client);

            log.info("=".repeat(50));

            // Test 2: Allowed command
            log.info("Test 2: Trying an allowed command (echo)...");
            client.query("Run the bash command: echo 'Hello from hooks!'").join();
            consumeUntilResult(client);

        } catch (Exception e) {
            log.error("Error: {}", e.getMessage(), e);
        }
    }

    /**
     * Example 2: PostToolUse Hook - Review tool output
     */
    static void postToolUseExample() {
        log.info("=== PostToolUse Example ===");
        log.info("This example reviews tool output and adds context.");

        Hook reviewToolOutput = (input, toolUseId, context) -> {
            Object toolResponse = input.get("tool_response");

            // Check if output contains an error
            if (toolResponse != null && toolResponse.toString().toLowerCase().contains("error")) {
                log.warn("⚠️  Tool execution produced an error");

                Map<String, Object> result = new HashMap<>();
                result.put("systemMessage", "⚠️ The command produced an error");
                result.put("reason", "Tool execution failed");

                Map<String, Object> hookOutput = new HashMap<>();
                hookOutput.put("hookEventName", "PostToolUse");
                hookOutput.put("additionalContext",
                    "The command encountered an error. Try a different approach.");
                result.put("hookSpecificOutput", hookOutput);

                return CompletableFuture.completedFuture(result);
            }

            return CompletableFuture.completedFuture(new HashMap<>());
        };

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
            .allowedTool("Bash")
            .hooks(Map.of(
                "PostToolUse", List.of(
                    new HookMatcher("Bash", List.of(reviewToolOutput))
                )
            ))
            .build();

        try (ClaudeSDKClient client = new ClaudeSDKClient(options)) {
            client.connect().join();
            log.info("Running command that will produce an error...");
            client.query("Run this command: ls /nonexistent_directory").join();
            consumeUntilResult(client);

        } catch (Exception e) {
            log.error("Error: {}", e.getMessage(), e);
        }
    }

    /**
     * Example 3: Strict Approval Hook - Control Write operations
     */
    static void strictApprovalExample() {
        log.info("=== Strict Approval Example ===");
        log.info("This example blocks writes to files containing 'important'.");

        Hook strictApproval = (input, toolUseId, context) -> {
            String toolName = (String) input.get("tool_name");
            @SuppressWarnings("unchecked")
            Map<String, Object> toolInput = (Map<String, Object>) input.get("tool_input");

            if ("Write".equals(toolName)) {
                String filePath = (String) toolInput.get("file_path");

                if (filePath != null && filePath.toLowerCase().contains("important")) {
                    log.warn("🚫 Blocked write to: {}", filePath);

                    Map<String, Object> result = new HashMap<>();
                    result.put("reason", "Security policy blocks writes to important files");
                    result.put("systemMessage", "🚫 Write operation blocked");

                    Map<String, Object> hookOutput = new HashMap<>();
                    hookOutput.put("hookEventName", "PreToolUse");
                    hookOutput.put("permissionDecision", "deny");
                    hookOutput.put("permissionDecisionReason",
                        "Files with 'important' in name are protected");
                    result.put("hookSpecificOutput", hookOutput);

                    return CompletableFuture.completedFuture(result);
                }

                // Allow other writes
                Map<String, Object> result = new HashMap<>();
                Map<String, Object> hookOutput = new HashMap<>();
                hookOutput.put("hookEventName", "PreToolUse");
                hookOutput.put("permissionDecision", "allow");
                hookOutput.put("permissionDecisionReason", "Security check passed");
                result.put("hookSpecificOutput", hookOutput);

                return CompletableFuture.completedFuture(result);
            }

            return CompletableFuture.completedFuture(new HashMap<>());
        };

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
            .allowedTool("Write")
            .allowedTool("Bash")
            .hooks(Map.of(
                "PreToolUse", List.of(
                    new HookMatcher("Write", List.of(strictApproval))
                )
            ))
            .build();

        try (ClaudeSDKClient client = new ClaudeSDKClient(options)) {
            client.connect().join();

            // Test 1: Blocked write
            log.info("Test 1: Trying to write to important_config.txt (blocked)...");
            client.query("Write 'test' to important_config.txt").join();
            consumeUntilResult(client);
            log.info("=".repeat(50));

            // Test 2: Allowed write
            log.info("Test 2: Trying to write to regular_file.txt (allowed)...");
            client.query("Write 'test' to regular_file.txt").join();
            consumeUntilResult(client);

        } catch (Exception e) {
            log.error("Error: {}", e.getMessage(), e);
        }
    }

    /**
     * Helper method to display messages
     */
    static void consumeUntilResult(ClaudeSDKClient client) {
        Stream<Message> stream = client.receiveMessages();
        try {
            Iterator<Message> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Message msg = iterator.next();
                displayMessage(msg);
                if (msg instanceof ResultMessage) {
                    break;
                }
            }
        } finally {
            stream.close();
        }
    }

    /**
     * Helper method to display messages
     */
    static void displayMessage(Message msg) {
        if (msg instanceof AssistantMessage) {
            AssistantMessage assistantMsg = (AssistantMessage) msg;
            assistantMsg.getContent().forEach(block -> {
                if (block instanceof TextBlock) {
                    TextBlock textBlock = (TextBlock) block;
                    log.info("Claude: {}", textBlock.getText());
                }
            });
        } else if (msg instanceof ResultMessage) {
            log.info("Result ended");
        }
    }
}
