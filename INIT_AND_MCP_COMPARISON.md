# Package Exports and MCP Support Comparison

## Overview
Comparison of Python's `__init__.py` (355 lines) with Java's package organization

**Status**: Java has basic MCP support but missing complete MCP server implementation (~60% complete)

---

## Python __init__.py Structure (355 lines)

The Python SDK's `__init__.py` serves three main purposes:

### 1. Public API Exports (Lines 1-54, 298-355)
Exports all public types, functions, and classes via `__all__`

**Categories:**
- Main functions: `query`, `ClaudeSDKClient`
- Message types: `UserMessage`, `AssistantMessage`, `SystemMessage`, `ResultMessage`
- Content blocks: `TextBlock`, `ThinkingBlock`, `ToolUseBlock`, `ToolResultBlock`
- Configuration: `ClaudeAgentOptions`, `AgentDefinition`, `SettingSource`
- Permissions: `CanUseTool`, `PermissionResult`, `PermissionUpdate`
- Hooks: `HookCallback`, `HookInput`, `PreToolUseHookInput`, etc.
- MCP: `McpServerConfig`, `McpSdkServerConfig`, `create_sdk_mcp_server`, `tool`, `SdkMcpTool`
- Errors: `ClaudeSDKError`, `CLIConnectionError`, `CLINotFoundError`, `ProcessError`
- Transport: `Transport`

### 2. MCP Tool Support (Lines 56-131)

**SdkMcpTool Class (Lines 61-68):**
```python
@dataclass
class SdkMcpTool(Generic[T]):
    """Definition for an SDK MCP tool."""
    name: str
    description: str
    input_schema: type[T] | dict[str, Any]
    handler: Callable[[T], Awaitable[dict[str, Any]]]
```

**@tool Decorator (Lines 71-131):**
```python
def tool(
    name: str, description: str, input_schema: type | dict[str, Any]
) -> Callable[[Callable[[Any], Awaitable[dict[str, Any]]]], SdkMcpTool[Any]]:
    """Decorator for defining MCP tools with type safety."""
    def decorator(handler):
        return SdkMcpTool(
            name=name,
            description=description,
            input_schema=input_schema,
            handler=handler,
        )
    return decorator
```

**Features:**
- Python decorator syntax for ergonomic tool definition
- Type-safe tool creation with Generic[T]
- Async handler support with Awaitable
- 50 lines of docstring with examples

**Example Usage:**
```python
@tool("greet", "Greet a user", {"name": str})
async def greet(args):
    return {"content": [{"type": "text", "text": f"Hello, {args['name']}!"}]}
```

### 3. MCP Server Creation (Lines 134-295)

**create_sdk_mcp_server Function (162 lines):**
```python
def create_sdk_mcp_server(
    name: str, version: str = "1.0.0", tools: list[SdkMcpTool[Any]] | None = None
) -> McpSdkServerConfig:
    """Create an in-process MCP server."""
    from mcp.server import Server
    from mcp.types import ImageContent, TextContent, Tool

    # Create MCP server instance (Line 210)
    server = Server(name, version=version)

    if tools:
        # Store tools for access in handlers
        tool_map = {tool_def.name: tool_def for tool_def in tools}

        # Register list_tools handler (Lines 218-261)
        @server.list_tools()
        async def list_tools() -> list[Tool]:
            """Return the list of available tools."""
            tool_list = []
            for tool_def in tools:
                # Convert input_schema to JSON Schema format
                if isinstance(tool_def.input_schema, dict):
                    # Handle simple dict mapping (name: type)
                    if "type" in tool_def.input_schema and "properties" in tool_def.input_schema:
                        schema = tool_def.input_schema
                    else:
                        # Convert {name: str, age: int} to JSON Schema
                        properties = {}
                        for param_name, param_type in tool_def.input_schema.items():
                            if param_type is str:
                                properties[param_name] = {"type": "string"}
                            elif param_type is int:
                                properties[param_name] = {"type": "integer"}
                            elif param_type is float:
                                properties[param_name] = {"type": "number"}
                            elif param_type is bool:
                                properties[param_name] = {"type": "boolean"}
                            else:
                                properties[param_name] = {"type": "string"}
                        schema = {
                            "type": "object",
                            "properties": properties,
                            "required": list(properties.keys()),
                        }
                else:
                    # TypedDict or other types
                    schema = {"type": "object", "properties": {}}

                tool_list.append(Tool(
                    name=tool_def.name,
                    description=tool_def.description,
                    inputSchema=schema,
                ))
            return tool_list

        # Register call_tool handler (Lines 264-292)
        @server.call_tool()
        async def call_tool(name: str, arguments: dict[str, Any]) -> Any:
            """Execute a tool by name with given arguments."""
            if name not in tool_map:
                raise ValueError(f"Tool '{name}' not found")

            tool_def = tool_map[name]
            # Call the tool's handler
            result = await tool_def.handler(arguments)

            # Convert result to MCP format
            content: list[TextContent | ImageContent] = []
            if "content" in result:
                for item in result["content"]:
                    if item.get("type") == "text":
                        content.append(TextContent(type="text", text=item["text"]))
                    if item.get("type") == "image":
                        content.append(ImageContent(
                            type="image",
                            data=item["data"],
                            mimeType=item["mimeType"],
                        ))

            return content

    # Return SDK server configuration (Line 295)
    return McpSdkServerConfig(type="sdk", name=name, instance=server)
```

**Key Features:**
1. **Creates actual MCP Server instance** from `mcp.server.Server`
2. **Registers list_tools handler** - Exposes tools to Claude
3. **Registers call_tool handler** - Executes tools when called
4. **JSON Schema conversion** - Converts Python types to JSON Schema
5. **Result format conversion** - Converts tool results to MCP format
6. **Returns McpSdkServerConfig** with server instance

**Example Usage:**
```python
@tool("add", "Add numbers", {"a": float, "b": float})
async def add(args):
    return {"content": [{"type": "text", "text": f"Sum: {args['a'] + args['b']}"}]}

calculator = create_sdk_mcp_server(
    name="calculator",
    version="2.0.0",
    tools=[add]
)

options = ClaudeAgentOptions(
    mcp_servers={"calc": calculator}
)
```

---

## Java Package Organization

Java doesn't have an equivalent `__init__.py` file. Instead, it uses:
- **Package structure** for organization
- **Individual classes** for public API
- **JavaDoc** for documentation

### Main Entry Point: `ClaudeAgent.java` (81 lines)

Located at: `src/main/java/com/anthropic/claude/sdk/ClaudeAgent.java`

```java
public class ClaudeAgent {
    // Factory methods
    public static Iterator<Message> query(String prompt, ClaudeAgentOptions options, Transport transport)
    public static Iterator<Message> query(String prompt, ClaudeAgentOptions options)
    public static Iterator<Message> query(String prompt)

    public static ClaudeSDKClient createClient(ClaudeAgentOptions options)
    public static ClaudeSDKClient createClient()
}
```

**Status:** ✅ Complete - Provides convenient static API

### MCP Support: `com.anthropic.claude.sdk.mcp` Package

**Files:**
1. `SdkMcpTool.java` (87 lines)
2. `SdkMcpServer.java` (57 lines)
3. `McpSdkServerConfig.java`
4. `McpServerConfig.java` (interface)
5. `McpStdioServerConfig.java`
6. `McpHttpServerConfig.java`
7. `McpSSEServerConfig.java`
8. `ToolAnnotation.java`

### Java SdkMcpTool (87 lines)

```java
public class SdkMcpTool<T> {
    private final String name;
    private final String description;
    private final Class<T> inputClass;
    private final Map<String, Object> inputSchema;
    private final Function<T, CompletableFuture<Map<String, Object>>> handler;

    public static <T> Builder<T> builder(String name, String description) {
        return new Builder<>(name, description);
    }

    public static class Builder<T> {
        public Builder<T> inputClass(Class<T> inputClass) { ... }
        public Builder<T> inputSchema(Map<String, Object> inputSchema) { ... }
        public Builder<T> handler(Function<T, CompletableFuture<Map<String, Object>>> handler) { ... }
        public SdkMcpTool<T> build() { ... }
    }
}
```

**Features:**
- ✅ Generic type parameter `<T>`
- ✅ Builder pattern (idiomatic Java)
- ✅ CompletableFuture for async (Java's async)
- ❌ No decorator syntax (Java doesn't have decorators)

**Example Usage:**
```java
SdkMcpTool<GreetInput> greet = SdkMcpTool.<GreetInput>builder("greet", "Greet a user")
    .inputClass(GreetInput.class)
    .handler(args -> CompletableFuture.completedFuture(
        Map.of("content", List.of(
            Map.of("type", "text", "text", "Hello, " + args.getName())
        ))
    ))
    .build();
```

**Comparison:**
- Python: `@tool` decorator (concise)
- Java: Builder pattern (verbose but type-safe)

### Java SdkMcpServer (57 lines)

```java
public class SdkMcpServer {
    private final String name;
    private final String version;
    private final List<SdkMcpTool<?>> tools;

    public static SdkMcpServer create(String name, String version, List<SdkMcpTool<?>> tools) {
        return new SdkMcpServer(name, version, tools);
    }

    public static SdkMcpServer create(String name, List<SdkMcpTool<?>> tools) {
        return new SdkMcpServer(name, "1.0.0", tools);
    }
}
```

**Features:**
- ✅ Static factory methods
- ✅ Tool list storage
- ✅ Version management
- ❌ **NO actual MCP Server instance**
- ❌ **NO list_tools handler registration**
- ❌ **NO call_tool handler registration**
- ❌ **NO JSON Schema conversion**
- ❌ **NO MCP protocol implementation**

**Status:** ⚠️ **INCOMPLETE** - This is just a data container, not a functional MCP server

---

## Comparison Table

| Feature | Python | Java | Status |
|---------|--------|------|--------|
| **Package Exports** |
| Public API listing | `__all__` | Package structure | ✅ Different approach |
| Main entry point | `query()` function | `ClaudeAgent.query()` | ✅ Complete |
| Client factory | Direct instantiation | `ClaudeAgent.createClient()` | ✅ Complete |
| **MCP Tool Definition** |
| Tool class | `SdkMcpTool` dataclass | `SdkMcpTool<T>` class | ✅ Complete |
| Tool creation | `@tool` decorator | Builder pattern | ✅ Different syntax |
| Type safety | Generic[T] | Generic `<T>` | ✅ Both type-safe |
| Async handler | `Awaitable` | `CompletableFuture` | ✅ Both async |
| **MCP Server Creation** |
| Server factory | `create_sdk_mcp_server()` | `SdkMcpServer.create()` | ⚠️ Incomplete |
| MCP Server instance | ✅ Creates `mcp.server.Server` | ❌ No server instance | ❌ **Critical Gap** |
| list_tools handler | ✅ Registered | ❌ Missing | ❌ **Critical Gap** |
| call_tool handler | ✅ Registered | ❌ Missing | ❌ **Critical Gap** |
| JSON Schema conversion | ✅ Automatic | ❌ Missing | ❌ **Critical Gap** |
| Result format conversion | ✅ MCP format | ❌ Missing | ❌ **Critical Gap** |
| Tool execution | ✅ Via MCP protocol | ❌ Not implemented | ❌ **Critical Gap** |
| **Documentation** |
| API docs | Extensive docstrings | JavaDoc | ✅ Both complete |
| Examples | Inline in docstrings | Separate examples | ✅ Both have examples |

---

## Critical Missing Features in Java

### 1. **Complete MCP Server Implementation** (HIGHEST PRIORITY)

The Java `SdkMcpServer` class is just a data container. It needs:

**Missing Components:**
1. **Actual MCP Server instance** - Java needs an equivalent of Python's `mcp.server.Server`
2. **list_tools handler** - Register a handler that returns available tools
3. **call_tool handler** - Register a handler that executes tools
4. **JSON Schema conversion** - Convert Java types to JSON Schema automatically
5. **MCP protocol implementation** - Handle MCP protocol messages

**Python Implementation (Lines 210-292):**
```python
# Creates actual MCP server
server = Server(name, version=version)

# Registers handlers
@server.list_tools()
async def list_tools() -> list[Tool]:
    # ... returns tool list

@server.call_tool()
async def call_tool(name: str, arguments: dict[str, Any]):
    # ... executes tool
```

**Java Missing:**
```java
// Currently just stores metadata
public class SdkMcpServer {
    private final String name;
    private final List<SdkMcpTool<?>> tools;
    // NO actual server instance
    // NO handler registration
    // NO protocol implementation
}
```

**Impact:**
- Java SDK MCP servers **will not work**
- Tools cannot be listed or executed
- MCP protocol not implemented

### 2. **Automatic JSON Schema Generation**

Python automatically converts type hints to JSON Schema:
```python
{"name": str, "age": int}
→
{
    "type": "object",
    "properties": {
        "name": {"type": "string"},
        "age": {"type": "integer"}
    },
    "required": ["name", "age"]
}
```

Java needs similar functionality, possibly using:
- Reflection on input class
- Jackson annotations
- Manual schema definition

### 3. **MCP Java Library**

Python uses the official `mcp` package:
```python
from mcp.server import Server
from mcp.types import Tool, TextContent
```

Java needs:
- Either port the MCP Python library to Java
- Or implement MCP protocol from scratch
- Or use an existing Java MCP library (if available)

---

## Recommendations

### Immediate Actions (Critical):

1. **Implement MCP Server functionality**
   - Create actual server instance in `SdkMcpServer`
   - Register list_tools and call_tool handlers
   - Implement MCP protocol communication

2. **Add JSON Schema generation**
   - Use reflection to inspect tool input classes
   - Convert Java types to JSON Schema
   - Support Jackson annotations

3. **Add MCP library dependency**
   - Find or create Java MCP library
   - Implement Server class equivalent
   - Implement Tool types

### Optional Enhancements:

4. **Add annotation-based tool definition** (alternative to builder)
   ```java
   @McpTool(name = "greet", description = "Greet a user")
   public CompletableFuture<ToolResult> greet(
       @ToolParam(description = "User name") String name
   ) {
       // ...
   }
   ```

5. **Create comprehensive examples**
   - Add examples/ directory with MCP tool examples
   - Document full MCP server setup process

---

## Current Status

**Python MCP Support:** ✅ 100% Complete
- Full MCP server creation
- Tool registration and execution
- JSON Schema conversion
- Protocol implementation

**Java MCP Support:** ⚠️ ~40% Complete
- ✅ Tool definition (SdkMcpTool)
- ✅ Server container (SdkMcpServer)
- ❌ Server implementation (missing)
- ❌ Handler registration (missing)
- ❌ Protocol implementation (missing)
- ❌ Schema conversion (missing)

**Overall Package API:** ✅ 90% Complete
- All major types exported
- Main entry points available
- Only MCP server implementation missing

---

## Conclusion

The Java SDK has good structural foundation with `SdkMcpTool` and `SdkMcpServer` classes, but **lacks the critical MCP server implementation**. The `SdkMcpServer` class is currently just a data container and needs to be enhanced with actual MCP protocol handling, tool registration, and execution capabilities.

**Priority:** CRITICAL - Without this, SDK MCP servers will not function at all.
