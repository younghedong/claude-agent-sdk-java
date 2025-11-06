package com.anthropic.examples;

import com.anthropic.claude.sdk.client.ClaudeAgentOptions;
import com.anthropic.claude.sdk.client.ClaudeSDKClient;
import com.anthropic.claude.sdk.hooks.AbortSignal;
import com.anthropic.claude.sdk.hooks.HookCallback;
import com.anthropic.claude.sdk.hooks.HookEvent;
import com.anthropic.claude.sdk.hooks.HookMatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Example demonstrating abort signal usage in hooks.
 *
 * This example shows how to:
 * 1. Access the abort signal in hook callbacks
 * 2. Check if the operation has been aborted
 * 3. Register abort listeners for cleanup
 * 4. Handle graceful cancellation of long-running operations
 */
public class AbortSignalExample {

    public static void main(String[] args) {
        // Create a hook that demonstrates abort signal usage
        HookCallback longRunningHook = (input, toolUseId, context) -> {
            System.out.println("Hook started for tool: " + toolUseId);

            // Get the abort signal from context
            AbortSignal signal = (AbortSignal) context.get("signal");

            // Check if already aborted
            if (signal != null && signal.isAborted()) {
                System.out.println("Hook was already aborted before starting");
                Map<String, Object> result = new HashMap<>();
                result.put("interrupt", true);
                return CompletableFuture.completedFuture(result);
            }

            // Register an abort listener for cleanup
            if (signal != null) {
                signal.onAbort(v -> {
                    System.out.println("Abort signal received! Cleaning up...");
                    // Perform cleanup operations here
                });
            }

            // Simulate a long-running operation
            return CompletableFuture.supplyAsync(() -> {
                try {
                    for (int i = 0; i < 10; i++) {
                        // Check abort signal periodically
                        if (signal != null && signal.isAborted()) {
                            System.out.println("Operation aborted at step " + i);
                            Map<String, Object> result = new HashMap<>();
                            result.put("interrupt", true);
                            result.put("message", "Operation aborted by user");
                            return result;
                        }

                        // Simulate work
                        Thread.sleep(1000);
                        System.out.println("Processing step " + i);
                    }

                    // Operation completed successfully
                    System.out.println("Hook completed successfully");
                    Map<String, Object> result = new HashMap<>();
                    result.put("continue", true);
                    return result;

                } catch (InterruptedException e) {
                    System.out.println("Hook interrupted: " + e.getMessage());
                    Map<String, Object> result = new HashMap<>();
                    result.put("interrupt", true);
                    return result;
                }
            });
        };

        // Create a hook that uses throwIfAborted for simpler abort checking
        HookCallback simpleAbortHook = (input, toolUseId, context) -> {
            AbortSignal signal = (AbortSignal) context.get("signal");

            return CompletableFuture.supplyAsync(() -> {
                try {
                    // Perform operation with abort checks
                    for (int i = 0; i < 5; i++) {
                        // This will throw if aborted
                        if (signal != null) {
                            signal.throwIfAborted();
                        }

                        Thread.sleep(500);
                        System.out.println("Step " + i);
                    }

                    Map<String, Object> result = new HashMap<>();
                    result.put("continue", true);
                    return result;

                } catch (AbortSignal.AbortException e) {
                    System.out.println("Aborted: " + e.getMessage());
                    Map<String, Object> result = new HashMap<>();
                    result.put("interrupt", true);
                    return result;
                } catch (InterruptedException e) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("interrupt", true);
                    return result;
                }
            });
        };

        // Configure hooks
        Map<String, List<HookMatcher>> hooks = new HashMap<>();

        List<HookMatcher> preToolUseHooks = new ArrayList<>();
        preToolUseHooks.add(HookMatcher.builder()
                .addHook(longRunningHook)
                .build());
        hooks.put(HookEvent.PRE_TOOL_USE.getValue(), preToolUseHooks);

        List<HookMatcher> postToolUseHooks = new ArrayList<>();
        postToolUseHooks.add(HookMatcher.builder()
                .addHook(simpleAbortHook)
                .build());
        hooks.put(HookEvent.POST_TOOL_USE.getValue(), postToolUseHooks);

        // Create client with hooks
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .hooks(hooks)
                .build();

        try (ClaudeSDKClient client = new ClaudeSDKClient(options, null)) {
            // Connect and start
            client.connect("Process this file using available tools");

            // Simulate: Let it run for a bit, then interrupt
            Thread.sleep(3000);

            System.out.println("\n=== Sending interrupt signal ===\n");
            client.interrupt().whenComplete((result, error) -> {
                if (error != null) {
                    System.err.println("Interrupt failed: " + error.getMessage());
                } else {
                    System.out.println("Interrupt acknowledged: " + result);
                }
            });

            // Wait a bit more
            Thread.sleep(2000);

            System.out.println("\n=== Example completed ===");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
