package com.anthropic.claude.sdk.examples;

import com.anthropic.claude.sdk.client.ClaudeSDKClient;
import com.anthropic.claude.sdk.types.content.TextBlock;
import com.anthropic.claude.sdk.types.messages.AssistantMessage;
import com.anthropic.claude.sdk.types.messages.Message;
import com.anthropic.claude.sdk.types.messages.ResultMessage;
import com.anthropic.claude.sdk.types.options.ClaudeAgentOptions;
import com.anthropic.claude.sdk.types.permissions.PermissionResult;
import com.anthropic.claude.sdk.types.permissions.ToolPermissionCallback;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Tool Permission Callback Example.
 *
 * Demonstrates how to use tool permission callbacks to:
 * 1. Allow/deny tools based on type
 * 2. Modify tool inputs for safety
 * 3. Log tool usage
 * 4. Block dangerous commands
 *
 * Usage:
 *   java ToolPermissionExample
 */
@Slf4j
public class ToolPermissionExample {

    // Track tool usage for demonstration
    static List<Map<String, Object>> toolUsageLog = new ArrayList<>();

    public static void main(String[] args) {
        log.info("=".repeat(60));
        log.info("Tool Permission Callback Example");
        log.info("=".repeat(60));
        log.info("This example demonstrates how to:");
        log.info("1. Allow/deny tools based on type");
        log.info("2. Modify tool inputs for safety");
        log.info("3. Log tool usage");
        log.info("4. Block dangerous commands");
        log.info("=".repeat(60));

        runExample();
    }

    static void runExample() {
        // Define our permission callback
        ToolPermissionCallback permissionCallback = (toolName, toolInput, context) -> {
            // Log the tool request
            Map<String, Object> logEntry = new HashMap<>();
            logEntry.put("tool", toolName);
            logEntry.put("input", toolInput);
            logEntry.put("suggestions", context.getSuggestions());
            toolUsageLog.add(logEntry);

            log.debug("🔧 Tool Permission Request: {}", toolName);
            log.debug("   Input: {}", toolInput);

            // Always allow read operations
            if (List.of("Read", "Glob", "Grep").contains(toolName)) {
                log.debug("   ✅ Automatically allowing {} (read-only)", toolName);
                return CompletableFuture.completedFuture(PermissionResult.allow());
            }

            // Deny write operations to system directories
            if (List.of("Write", "Edit", "MultiEdit").contains(toolName)) {
                String filePath = (String) toolInput.get("file_path");

                if (filePath != null) {
                    if (filePath.startsWith("/etc/") || filePath.startsWith("/usr/")) {
                        log.warn("   ❌ Denying write to system directory: {}", filePath);
                        return CompletableFuture.completedFuture(
                            PermissionResult.deny("Cannot write to system directory: " + filePath)
                        );
                    }

                    // Redirect writes to a safe directory
                    if (!filePath.startsWith("/tmp/") && !filePath.startsWith("./")) {
                        String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
                        String safePath = "./safe_output/" + fileName;
                        log.warn("   ⚠️  Redirecting write from {} to {}", filePath, safePath);

                        Map<String, Object> modifiedInput = new HashMap<>(toolInput);
                        modifiedInput.put("file_path", safePath);

                        return CompletableFuture.completedFuture(
                            PermissionResult.allow(modifiedInput)
                        );
                    }
                }
            }

            // Check dangerous bash commands
            if ("Bash".equals(toolName)) {
                String command = (String) toolInput.get("command");
                String[] dangerousCommands = {"rm -rf", "sudo", "chmod 777", "dd if=", "mkfs"};

                for (String dangerous : dangerousCommands) {
                    if (command != null && command.contains(dangerous)) {
                        log.warn("   ❌ Denying dangerous command: {}", command);
                        return CompletableFuture.completedFuture(
                            PermissionResult.deny("Dangerous command pattern detected: " + dangerous)
                        );
                    }
                }

                log.debug("   ✅ Allowing bash command: {}", command);
                return CompletableFuture.completedFuture(PermissionResult.allow());
            }

            // For other tools, allow by default
            log.debug("   ✅ Allowing {}", toolName);
            return CompletableFuture.completedFuture(PermissionResult.allow());
        };

        // Configure options with our callback
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
            .canUseTool(permissionCallback)
            .build();

        // Create streaming client and send a query
        try (ClaudeSDKClient client = new ClaudeSDKClient(options)) {
            client.connect().join();
            log.info("📝 Sending query to Claude...");

            client.query(
                "Please do the following:\n" +
                "1. List the files in the current directory\n" +
                "2. Create a simple Python hello world script at hello.py\n" +
                "3. Run the script to test it"
            ).join();

            log.info("📨 Receiving response...");
            streamUntilResult(client);

            // Print tool usage summary
            log.info("=".repeat(60));
            log.info("Tool Usage Summary");
            log.info("=".repeat(60));

            for (int i = 0; i < toolUsageLog.size(); i++) {
                Map<String, Object> usage = toolUsageLog.get(i);
                log.info("{}. Tool: {}", (i + 1), usage.get("tool"));
                log.info("   Input: {}", usage.get("input"));
                if (usage.get("suggestions") != null) {
                    log.info("   Suggestions: {}", usage.get("suggestions"));
                }
            }

        } catch (Exception e) {
            log.error("Error: {}", e.getMessage(), e);
        }
    }

    private static void streamUntilResult(ClaudeSDKClient client) {
        Stream<Message> stream = client.receiveMessages();
        try {
            int messageCount = 0;
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                Message message = iterator.next();
                messageCount++;

                if (message instanceof AssistantMessage) {
                    AssistantMessage assistantMsg = (AssistantMessage) message;
                    assistantMsg.getContent().forEach(block -> {
                        if (block instanceof TextBlock) {
                            TextBlock textBlock = (TextBlock) block;
                            log.info("💬 Claude: {}", textBlock.getText());
                        }
                    });
                } else if (message instanceof ResultMessage) {
                    ResultMessage resultMsg = (ResultMessage) message;
                    log.info("✅ Task completed!");
                    log.info("   Duration: {}ms", resultMsg.getDurationMs());
                    if (resultMsg.getTotalCostUsd() != null) {
                        log.info("   Cost: ${}", String.format("%.4f", resultMsg.getTotalCostUsd()));
                    }
                    log.info("   Messages processed: {}", messageCount);
                    break;
                }
            }
        } finally {
            stream.close();
        }
    }
}
