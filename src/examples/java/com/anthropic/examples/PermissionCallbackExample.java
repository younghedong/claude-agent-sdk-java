package com.anthropic.examples;

import com.anthropic.claude.sdk.client.ClaudeAgentOptions;
import com.anthropic.claude.sdk.client.ClaudeSDKClient;
import com.anthropic.claude.sdk.types.PermissionMode;
import com.anthropic.claude.sdk.types.PermissionResultAllow;
import com.anthropic.claude.sdk.types.PermissionResultDeny;
import com.anthropic.claude.sdk.types.PermissionUpdate;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Example demonstrating permission callback usage.
 *
 * This example shows how to:
 * 1. Implement a canUseTool callback to control tool access
 * 2. Allow tools with potentially modified inputs
 * 3. Deny tools with custom messages
 * 4. Update permissions dynamically
 * 5. Use permission suggestions from the CLI
 */
public class PermissionCallbackExample {

    public static void main(String[] args) {
        // Track which tools have been used
        Set<String> usedTools = new HashSet<>();

        // Create permission callback
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .permissionMode(PermissionMode.CALLBACK)
                .canUseTool((toolName, input, context) -> {
                    System.out.println("\n=== Permission Check ===");
                    System.out.println("Tool: " + toolName);
                    System.out.println("Input: " + input);
                    System.out.println("Suggestions: " + context.getSuggestions());

                    // Example 1: Allow safe read-only tools without modification
                    if (toolName.equals("list_files") || toolName.equals("read_file")) {
                        System.out.println("Decision: ALLOW (safe read-only operation)");
                        return CompletableFuture.completedFuture(
                                PermissionResultAllow.allow()
                        );
                    }

                    // Example 2: Allow write operations with modified input (add safety checks)
                    if (toolName.equals("write_file")) {
                        String path = (String) input.get("path");

                        // Don't allow writing to system files
                        if (path != null && path.startsWith("/etc/")) {
                            System.out.println("Decision: DENY (system file access)");
                            return CompletableFuture.completedFuture(
                                    PermissionResultDeny.deny(
                                            "Cannot write to system files in /etc/",
                                            false
                                    )
                            );
                        }

                        // Add backup flag to the input
                        Map<String, Object> modifiedInput = new HashMap<>(input);
                        modifiedInput.put("create_backup", true);

                        System.out.println("Decision: ALLOW with modified input (backup enabled)");
                        return CompletableFuture.completedFuture(
                                PermissionResultAllow.builder()
                                        .updatedInput(modifiedInput)
                                        .build()
                        );
                    }

                    // Example 3: Allow bash but update permissions for future
                    if (toolName.equals("bash")) {
                        String command = (String) input.get("command");

                        // Deny dangerous commands
                        if (command != null && (command.contains("rm -rf") || command.contains("sudo"))) {
                            System.out.println("Decision: DENY with interrupt (dangerous command)");
                            return CompletableFuture.completedFuture(
                                    PermissionResultDeny.deny(
                                            "Dangerous command detected: " + command,
                                            true  // Interrupt the agent loop
                                    )
                            );
                        }

                        // First time: ask for permission
                        if (!usedTools.contains("bash")) {
                            usedTools.add("bash");

                            // In a real application, you might show a UI prompt here
                            System.out.println("Decision: ALLOW and remember (first bash use)");

                            // Update permissions to auto-allow bash in future
                            List<PermissionUpdate> updates = new ArrayList<>();
                            updates.add(PermissionUpdate.builder()
                                    .tool("bash")
                                    .permission("allow")
                                    .build());

                            return CompletableFuture.completedFuture(
                                    PermissionResultAllow.builder()
                                            .updatedPermissions(updates)
                                            .build()
                            );
                        }

                        System.out.println("Decision: ALLOW (previously approved)");
                        return CompletableFuture.completedFuture(
                                PermissionResultAllow.allow()
                        );
                    }

                    // Example 4: Rate limit a tool
                    if (toolName.equals("web_search")) {
                        int searchCount = usedTools.stream()
                                .filter(t -> t.startsWith("web_search_"))
                                .mapToInt(t -> 1)
                                .sum();

                        if (searchCount >= 3) {
                            System.out.println("Decision: DENY (rate limit exceeded)");
                            return CompletableFuture.completedFuture(
                                    PermissionResultDeny.deny(
                                            "Web search rate limit exceeded (3 per session)",
                                            false
                                    )
                            );
                        }

                        usedTools.add("web_search_" + searchCount);
                        System.out.println("Decision: ALLOW (search " + (searchCount + 1) + "/3)");
                        return CompletableFuture.completedFuture(
                                PermissionResultAllow.allow()
                        );
                    }

                    // Example 5: Use suggestions from CLI
                    if (!context.getSuggestions().isEmpty()) {
                        String suggestion = context.getSuggestions().get(0);
                        System.out.println("Using CLI suggestion: " + suggestion);

                        if ("allow".equals(suggestion)) {
                            return CompletableFuture.completedFuture(
                                    PermissionResultAllow.allow()
                            );
                        } else if ("deny".equals(suggestion)) {
                            return CompletableFuture.completedFuture(
                                    PermissionResultDeny.deny(
                                            "Denied based on CLI suggestion",
                                            false
                                    )
                            );
                        }
                    }

                    // Default: Allow unknown tools
                    System.out.println("Decision: ALLOW (default for unknown tool)");
                    return CompletableFuture.completedFuture(
                            PermissionResultAllow.allow()
                    );
                })
                .build();

        try (ClaudeSDKClient client = new ClaudeSDKClient(options, null)) {
            System.out.println("Starting client with permission callback...\n");

            // Connect and start
            client.connect("Please list files in the current directory and read README.md");

            // Wait for messages
            Thread.sleep(10000);

            System.out.println("\n=== Example completed ===");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
