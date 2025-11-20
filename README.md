# Claude Agent SDK for Java

Java SDK for Claude Code - Build AI agents with Claude.

This is a Java implementation of the [Claude Agent SDK](https://docs.anthropic.com/en/docs/claude-code/sdk), providing
the same functionality as the Python SDK but with Java's type safety and ecosystem.

## Features

- ✅ **One-shot queries** via static `query()` method
- ✅ **Interactive sessions** via `ClaudeSDKClient`
- ✅ **Tool permissions** with callback support
- ✅ **Hooks** for deterministic processing at key points
- ✅ **Type-safe** with Java 17 records and sealed interfaces
- ✅ **Builder pattern** for easy configuration
- ✅ **CompletableFuture** based async API
- ✅ **MCP SDK Servers** (in-process tools) with `SdkMcpServer`

## Prerequisites

- Java 11 or higher
- Node.js
- Claude Code 2.0.0+: `npm install -g @anthropic-ai/claude-code`

## Installation

### Maven

```xml

<dependency>
    <groupId>com.anthropic</groupId>
    <artifactId>claude-agent-sdk</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Gradle

```gradle
implementation 'com.anthropic:claude-agent-sdk:0.1.0'
```

## Quick Start

### Simple Query

```java
import com.anthropic.claude.sdk.ClaudeAgentSdk;
import com.anthropic.claude.sdk.types.messages.*;
import com.anthropic.claude.sdk.types.content.*;

public class Example {
    public static void main(String[] args) {
        // Simple one-shot query
        ClaudeAgentSdk.query("What is 2 + 2?")
                .forEach(message -> {
                    if (message instanceof AssistantMessage assistantMsg) {
                        assistantMsg.content().forEach(block -> {
                            if (block instanceof TextBlock textBlock) {
                                System.out.println(textBlock.text());
                            }
                        });
                    }
                });
    }
}
```

### Query with Options

```java
import com.anthropic.claude.sdk.types.options.*;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
        .allowedTools("Read", "Write", "Bash")
        .permissionMode(PermissionMode.ACCEPT_EDITS)
        .maxTurns(10)
        .model("claude-sonnet-4")
        .build();

ClaudeAgentSdk.

query("Analyze this codebase",options)
    .

forEach(System.out::println);
```

### Interactive Session

```java
import com.anthropic.claude.sdk.client.ClaudeSDKClient;
import com.anthropic.claude.sdk.types.messages.Message;
import com.anthropic.claude.sdk.types.messages.ResultMessage;

try(ClaudeSDKClient client = new ClaudeSDKClient(options)){
        client.

connect().

join();
    client.

query("Hello Claude").

join();

    client.

receiveMessages()
        .

takeWhile(message ->!(message instanceof ResultMessage))
        .

forEach(System.out::println);
}
```

### Tool Permissions

```java
import com.anthropic.claude.sdk.types.permissions.*;

import java.util.concurrent.CompletableFuture;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
        .allowedTools("Bash")
        .canUseTool((toolName, toolInput, context) -> {
            String command = (String) toolInput.get("command");

            if (command.contains("rm -rf")) {
                return CompletableFuture.completedFuture(
                        PermissionResult.deny("Dangerous command blocked")
                );
            }

            return CompletableFuture.completedFuture(
                    PermissionResult.allow()
            );
        })
        .build();
```

### Hooks

```java
import com.anthropic.claude.sdk.types.hooks.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

Hook preToolUseHook = (input, toolUseId, context) -> {
    System.out.println("Tool about to be used: " + input.get("tool_name"));

    // Allow or deny tool execution
    Map<String, Object> result = new HashMap<>();
    result.put("permissionDecision", "allow");

    return CompletableFuture.completedFuture(result);
};

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
        .hooks(Map.of(
                "PreToolUse", List.of(
                        new HookMatcher("Bash", List.of(preToolUseHook))
                )
        ))
        .build();

try(
ClaudeSDKClient client = new ClaudeSDKClient(options)){
        client.

connect().

join();
    client.

query("Run the bash command: echo 'Hello hooks!'").

join();
    client.

receiveMessages().

forEach(System.out::println);
}
```

## Architecture

The Java SDK mirrors the Python SDK architecture:

```
┌─────────────────────────────────────┐
│  ClaudeAgentSdk / ClaudeSDKClient   │ (Public API)
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│  MessageParser                      │ (Protocol Layer)
│  - Parse JSON messages              │
│  - Type conversion                  │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│  SubprocessTransport                │ (Transport Layer)
│  - Process management               │
│  - I/O stream handling              │
│  - Command building                 │
└─────────────────────────────────────┘
               │
               ▼
        Claude Code CLI
```

### Key Components

- **`ClaudeAgentSdk`**: Static entry point for simple queries
- **`ClaudeSDKClient`**: Full-featured client for interactive sessions
- **`ClaudeAgentOptions`**: Builder-based configuration
- **`SubprocessTransport`**: Manages CLI subprocess and I/O
- **`MessageParser`**: Parses JSON messages into typed objects
- **Type system**: Sealed interfaces + records for type safety

## Type System

The SDK uses Java 17 features for maximum type safety:

### Messages

```java
sealed interface Message
        permits UserMessage, AssistantMessage, SystemMessage, ResultMessage

record AssistantMessage(
        List<ContentBlock>content,
String model,
String parentToolUseId
)implements Message
```

### Content Blocks

```java
sealed interface ContentBlock
        permits TextBlock, ThinkingBlock, ToolUseBlock, ToolResultBlock

record TextBlock(String text)implements ContentBlock

record ToolUseBlock(String id, String name, Map<String, Object> input) implements ContentBlock
```

## Comparison with Python SDK

| Feature              | Python SDK          | Java SDK                                   |
|----------------------|---------------------|--------------------------------------------|
| One-shot queries     | ✅ `query()`         | ✅ `ClaudeAgentSdk.query()`                 |
| Interactive sessions | ✅ `ClaudeSDKClient` | ✅ `ClaudeSDKClient`                        |
| Type safety          | TypedDict (runtime) | Sealed interfaces + Records (compile-time) |
| Async                | `async`/`await`     | `CompletableFuture`                        |
| Hooks                | ✅                   | ✅                                          |
| Tool permissions     | ✅                   | ✅                                          |
| SDK MCP Servers      | ✅                   | 🚧 Coming soon                             |
| Builder pattern      | ❌                   | ✅                                          |

## CLI Finding Logic

The SDK searches for the `claude` CLI in the following order:

1. System PATH
2. `~/.npm-global/bin/claude`
3. `/usr/local/bin/claude`
4. `~/.local/bin/claude`
5. `~/node_modules/.bin/claude`
6. `~/.yarn/bin/claude`
7. `~/.claude/local/claude`

Override with:

```java
ClaudeAgentOptions.builder()
    .

cliPath(Path.of("/custom/path/to/claude"))
        .

build()
```

## Error Handling

```java
import com.anthropic.claude.sdk.exceptions.*;

try{
        ClaudeAgentSdk.query("Hello").

forEach(System.out::println);
}catch(
CLINotFoundException e){
        System.err.

println("Claude Code not installed");
}catch(
CLIConnectionException e){
        System.err.

println("Failed to connect to CLI");
}catch(
ProcessException e){
        System.err.

println("CLI process failed: "+e.getExitCode());
        }catch(
MessageParseException e){
        System.err.

println("Invalid message: "+e.getRawData());
        }
```

## Building from Source

```bash
git clone https://github.com/anthropics/claude-agent-sdk-java
cd claude-agent-sdk-java
mvn clean install
```

## Examples

See the `examples/` directory for more examples:

- `QuickStart.java` - Basic usage examples
- More examples coming soon!

## License

MIT

## Contributing

Contributions are welcome! Please see CONTRIBUTING.md for guidelines.

## Related Projects

- [Claude Agent SDK for Python](https://github.com/anthropics/claude-agent-sdk-python)
- [Claude Code Documentation](https://docs.anthropic.com/en/docs/claude-code)
