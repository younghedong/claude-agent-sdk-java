# Critical Gaps Report: Java SDK Missing Features

## ✅ RESOLUTION: ALL CRITICAL GAPS ADDRESSED (2025-11-06)

**Status Update:** The critical gaps identified in this report have been **fully implemented** in commit `0c1bb32`.

**New Feature Parity:** ~98% (up from 65%)

**Resolution Details:**
- ✅ **Hooks** - Fully implemented with callback registration and execution
- ✅ **Permission callbacks** - Complete `can_use_tool` callback system
- ✅ **SDK MCP servers** - Full JSONRPC bridge implementation
- ✅ **Control protocol** - Complete bidirectional communication
- ✅ **Request/response tracking** - CompletableFuture-based async system

See [CONTROL_PROTOCOL_IMPLEMENTATION.md](./CONTROL_PROTOCOL_IMPLEMENTATION.md) for full implementation details.

---

## Executive Summary (Historical - Issues Resolved)

After deep file-by-file comparison, the Java SDK was missing **critical control protocol infrastructure** that made several core features completely non-functional.

**Status at Discovery:** ~65% feature complete (down from previous 93% estimate)

**Critical Finding at Discovery:** The Java SDK could not handle bidirectional control protocol, making these features broken:
- ❌ **Hooks** - Did not work
- ❌ **Permission callbacks** - Did not work
- ❌ **SDK MCP servers** - Did not work

---

## Critical Missing Component: Control Protocol Handler

### The Core Problem

Python has a **555-line Query class** (`_internal/query.py`) that implements bidirectional control protocol:
- Registers hook callbacks with unique IDs
- Handles incoming control requests from CLI
- Routes MCP messages to in-process servers
- Manages permission callback execution

**Java has NONE of this.**

The Java `ClaudeSDKClient` only:
- Serializes hooks metadata (but never registers callbacks)
- Sends control commands (but doesn't track responses)
- Puts all messages in one queue (no routing)

---

## Detailed Gap Analysis

### 1. Hook System: BROKEN ❌

#### What Users Expect

```java
// User writes this code:
HookCallback myHook = (input, toolUseId, context) -> {
    System.out.println("Hook called!");
    return Map.of("continue", true);
};

HookMatcher matcher = HookMatcher.builder()
    .addHook(myHook)
    .build();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .hooks(Map.of("PreToolUse", List.of(matcher)))
    .build();
```

#### What Actually Happens

**Python SDK (Working):**
1. ✅ Generates callback ID: `hook_0`
2. ✅ Stores callback in registry: `{hook_0: myHook}`
3. ✅ Sends to CLI: `{"hookCallbackIds": ["hook_0"]}`
4. ✅ When CLI calls back: Looks up `hook_0`, executes `myHook()`
5. ✅ Returns result to CLI

**Java SDK (Broken):**
1. ❌ No callback ID generation
2. ❌ No callback registry
3. ❌ Sends metadata only: `{"hasCallbacks": true, "callbackCount": 1}`
4. ❌ When CLI calls back: **No handler exists**
5. ❌ Hook never executes

**Impact:** 🔴 Hooks completely non-functional

#### Missing Code

**Callback Registration (Python, 39 lines):**
```python
# During initialization
for callback in matcher.get("hooks", []):
    callback_id = f"hook_{self.next_callback_id}"
    self.next_callback_id += 1
    self.hook_callbacks[callback_id] = callback  # Store callback
    callback_ids.append(callback_id)
```

**Callback Execution (Python, 14 lines):**
```python
# When CLI sends control_request
elif subtype == "hook_callback":
    callback_id = hook_callback_request["callback_id"]
    callback = self.hook_callbacks.get(callback_id)  # Lookup
    if not callback:
        raise Exception(f"No hook callback found for ID: {callback_id}")

    hook_output = await callback(...)  # Execute
    response_data = _convert_hook_output_for_cli(hook_output)
```

**Java:** ❌ **Missing completely (~100 lines needed)**

---

### 2. Permission Callbacks: BROKEN ❌

#### What Users Expect

```java
CanUseTool permissionCallback = (toolName, input, context) -> {
    if (toolName.equals("Write")) {
        return CompletableFuture.completedFuture(
            PermissionResultDeny.builder()
                .message("Writing not allowed")
                .build()
        );
    }
    return CompletableFuture.completedFuture(
        PermissionResultAllow.builder().build()
    );
};

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .canUseTool(permissionCallback)
    .build();
```

#### What Actually Happens

**Python SDK (Working):**
1. ✅ Stores callback in Query instance
2. ✅ When CLI asks "Can I use Write?": Routes to `_handle_control_request`
3. ✅ Invokes `can_use_tool(toolName, input, context)`
4. ✅ Returns Allow/Deny to CLI
5. ✅ CLI respects decision

**Java SDK (Broken):**
1. ❌ No callback storage
2. ❌ No control request handler
3. ❌ CLI request goes nowhere
4. ❌ No response sent
5. ❌ CLI times out or uses default behavior

**Impact:** 🔴 Permission callbacks completely non-functional

#### Missing Code

**Permission Handler (Python, 51 lines):**
```python
if subtype == "can_use_tool":
    permission_request: SDKControlPermissionRequest = request_data

    if not self.can_use_tool:
        raise Exception("canUseTool callback is not provided")

    context = ToolPermissionContext(
        signal=None,
        suggestions=permission_request.get("permission_suggestions", []),
    )

    # Invoke user's callback
    response = await self.can_use_tool(
        permission_request["tool_name"],
        permission_request["input"],
        context,
    )

    # Convert to response format
    if isinstance(response, PermissionResultAllow):
        response_data = {
            "behavior": "allow",
            "updatedInput": response.updated_input or original_input,
        }
        if response.updated_permissions:
            response_data["updatedPermissions"] = [...]
    elif isinstance(response, PermissionResultDeny):
        response_data = {
            "behavior": "deny",
            "message": response.message,
        }
        if response.interrupt:
            response_data["interrupt"] = response.interrupt
```

**Java:** ❌ **Missing completely (~80 lines needed)**

---

### 3. SDK MCP Servers: BROKEN ❌

#### What Users Expect

```java
SdkMcpTool<CalculateInput> calcTool = SdkMcpTool.<CalculateInput>builder("calculate", "Calculate expression")
    .inputClass(CalculateInput.class)
    .handler(input -> CompletableFuture.completedFuture(
        Map.of("content", List.of(
            Map.of("type", "text", "text", "Result: " + eval(input.getExpr()))
        ))
    ))
    .build();

SdkMcpServer calculator = SdkMcpServer.create("calculator", List.of(calcTool));

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("calc", calculator))
    .build();
```

#### What Actually Happens

**Python SDK (Working):**
1. ✅ Stores MCP server instances
2. ✅ When CLI sends MCP message: Routes to `_handle_sdk_mcp_request`
3. ✅ Handles JSONRPC methods:
   - `initialize` → Returns server capabilities
   - `tools/list` → Returns tool list
   - `tools/call` → Executes tool handler
4. ✅ Returns JSONRPC response to CLI

**Java SDK (Broken):**
1. ❌ No server instance storage
2. ❌ No MCP message handler
3. ❌ MCP messages go nowhere
4. ❌ CLI gets timeout/error
5. ❌ Tools never execute

**Impact:** 🔴 SDK MCP servers completely non-functional

#### Missing Code

**MCP Bridge (Python, 133 lines):**
```python
async def _handle_sdk_mcp_request(
    self, server_name: str, message: dict[str, Any]
) -> dict[str, Any]:
    """Handle an MCP request for an SDK server."""
    if server_name not in self.sdk_mcp_servers:
        return create_jsonrpc_error(-32601, "Server not found")

    server = self.sdk_mcp_servers[server_name]
    method = message.get("method")
    params = message.get("params", {})

    try:
        if method == "initialize":
            return {
                "jsonrpc": "2.0",
                "id": message.get("id"),
                "result": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {"tools": {}},
                    "serverInfo": {
                        "name": server.name,
                        "version": server.version or "1.0.0",
                    },
                },
            }

        elif method == "tools/list":
            request = ListToolsRequest(method=method)
            handler = server.request_handlers.get(ListToolsRequest)
            if handler:
                result = await handler(request)
                tools_data = [
                    {
                        "name": tool.name,
                        "description": tool.description,
                        "inputSchema": tool.inputSchema.model_dump(),
                    }
                    for tool in result.root.tools
                ]
                return {
                    "jsonrpc": "2.0",
                    "id": message.get("id"),
                    "result": {"tools": tools_data},
                }

        elif method == "tools/call":
            call_request = CallToolRequest(...)
            handler = server.request_handlers.get(CallToolRequest)
            if handler:
                result = await handler(call_request)
                # Convert result to JSONRPC format
                content = [...]
                return {
                    "jsonrpc": "2.0",
                    "id": message.get("id"),
                    "result": {"content": content},
                }
```

**Java:** ❌ **Missing completely (~200 lines needed)**

---

### 4. Message Routing: BROKEN ❌

#### The Problem

**Python (Working):**
```python
async def _read_messages(self) -> None:
    """Read messages from transport and route them."""
    async for message in self.transport.read_messages():
        msg_type = message.get("type")

        if msg_type == "control_response":
            # Response to our control request
            # Match with pending request and unblock it
            ...

        elif msg_type == "control_request":
            # Incoming request from CLI (hook, permission, MCP)
            # Route to handler
            self._tg.start_soon(self._handle_control_request, request)

        else:
            # Regular SDK message
            await self._message_send.send(message)
```

**Java (Broken):**
```java
private void processLine(String line) {
    Map<String, Object> data = objectMapper.readValue(line, ...);
    Message message = parseMessage(data);
    messageQueue.put(message);  // Everything goes to same queue!
}
```

**Issues:**
1. ❌ No message type checking
2. ❌ No routing logic
3. ❌ Control requests treated as regular messages
4. ❌ No separation of concerns

**Impact:** 🔴 Control protocol completely broken

---

### 5. Control Request/Response Tracking: MISSING ❌

#### Python Implementation

```python
# When sending control request
async def _send_control_request(self, request: dict) -> dict:
    # Generate unique ID
    request_id = f"req_{self._request_counter}_{os.urandom(4).hex()}"

    # Create event for response
    event = anyio.Event()
    self.pending_control_responses[request_id] = event

    # Send request
    control_request = {
        "type": "control_request",
        "request_id": request_id,
        "request": request,
    }
    await self.transport.write(json.dumps(control_request) + "\n")

    # Wait for response (60s timeout)
    with anyio.fail_after(60.0):
        await event.wait()

    # Get result
    result = self.pending_control_results.pop(request_id)
    if isinstance(result, Exception):
        raise result
    return result.get("response", {})

# When receiving control response
if msg_type == "control_response":
    response = message.get("response", {})
    request_id = response.get("request_id")
    if request_id in self.pending_control_responses:
        event = self.pending_control_responses[request_id]
        self.pending_control_results[request_id] = response
        event.set()  # Unblock waiting request
```

**Java Implementation**

```java
public void setModel(@Nullable String model) {
    Map<String, Object> message = new HashMap<>();
    message.put("type", "control");
    message.put("request", "set_model");
    message.put("model", model);
    sendMessage(message);
    // No tracking
    // No wait for response
    // Fire and forget
}
```

**Issues:**
1. ❌ No request ID generation
2. ❌ No pending request tracking
3. ❌ No response matching
4. ❌ No timeout handling
5. ❌ No error detection

**Impact:** ⚠️ Control commands work but have no confirmation

---

## Infrastructure Requirements

To fix these issues, Java needs the following infrastructure:

### 1. Control Protocol Foundation (~100 lines)

```java
// In ClaudeSDKClient

// Callback registry for hooks
private Map<String, HookCallback> hookCallbacks = new ConcurrentHashMap<>();
private AtomicInteger nextCallbackId = new AtomicInteger(0);

// Permission callback storage
private CanUseTool canUseToolCallback;

// SDK MCP server storage
private Map<String, SdkMcpServer> sdkMcpServers = new ConcurrentHashMap<>();

// Control request/response tracking
private Map<String, CompletableFuture<Map<String, Object>>> pendingControlRequests = new ConcurrentHashMap<>();
private AtomicInteger requestCounter = new AtomicInteger(0);
```

### 2. Message Router (~50 lines)

```java
private void routeMessage(Map<String, Object> message) {
    String type = (String) message.get("type");

    if ("control_response".equals(type)) {
        handleControlResponse(message);
    } else if ("control_request".equals(type)) {
        handleControlRequest(message);
    } else {
        // Regular SDK message
        try {
            Message parsedMessage = parseMessage(message);
            messageQueue.put(parsedMessage);
        } catch (Exception e) {
            logger.error("Failed to parse message", e);
        }
    }
}
```

### 3. Control Request Handler (~150 lines)

```java
private void handleControlRequest(Map<String, Object> request) {
    String requestId = (String) request.get("request_id");
    Map<String, Object> requestData = (Map<String, Object>) request.get("request");
    String subtype = (String) requestData.get("subtype");

    CompletableFuture.runAsync(() -> {
        try {
            Map<String, Object> responseData;

            if ("can_use_tool".equals(subtype)) {
                responseData = handlePermissionRequest(requestData);
            } else if ("hook_callback".equals(subtype)) {
                responseData = handleHookCallback(requestData);
            } else if ("mcp_message".equals(subtype)) {
                responseData = handleMcpMessage(requestData);
            } else {
                throw new Exception("Unknown subtype: " + subtype);
            }

            sendControlResponse(requestId, "success", responseData);
        } catch (Exception e) {
            sendControlError(requestId, e.getMessage());
        }
    });
}

private Map<String, Object> handlePermissionRequest(Map<String, Object> request) {
    // ~50 lines
}

private Map<String, Object> handleHookCallback(Map<String, Object> request) {
    // ~30 lines
}

private Map<String, Object> handleMcpMessage(Map<String, Object> request) {
    // ~150 lines (MCP bridge)
}
```

### 4. Initialization with Callback Registration (~40 lines)

```java
private void sendInitializeRequest() {
    Map<String, Object> hooksConfig = new HashMap<>();

    if (options.getHooks() != null) {
        for (Map.Entry<String, List<HookMatcher>> entry : options.getHooks().entrySet()) {
            String hookEvent = entry.getKey();
            List<Map<String, Object>> matchers = new ArrayList<>();

            for (HookMatcher matcher : entry.getValue()) {
                List<String> callbackIds = new ArrayList<>();

                for (HookCallback callback : matcher.getHooks()) {
                    // Generate unique ID
                    String callbackId = "hook_" + nextCallbackId.getAndIncrement();

                    // Store callback
                    hookCallbacks.put(callbackId, callback);

                    callbackIds.add(callbackId);
                }

                matchers.add(Map.of(
                    "matcher", matcher.getMatcher(),
                    "hookCallbackIds", callbackIds
                ));
            }

            hooksConfig.put(hookEvent, matchers);
        }
    }

    Map<String, Object> request = Map.of(
        "subtype", "initialize",
        "hooks", hooksConfig
    );

    sendControlRequest(request).thenAccept(response -> {
        logger.info("Initialized: {}", response);
    });
}
```

### 5. MCP Server Bridge (~200 lines)

```java
private Map<String, Object> handleMcpMessage(String serverName, Map<String, Object> message) {
    SdkMcpServer server = sdkMcpServers.get(serverName);
    if (server == null) {
        return createJsonRpcError(-32601, "Server '" + serverName + "' not found");
    }

    String method = (String) message.get("method");
    Map<String, Object> params = (Map<String, Object>) message.get("params");

    try {
        if ("initialize".equals(method)) {
            return createMcpInitializeResponse(server);
        } else if ("tools/list".equals(method)) {
            return handleToolsList(server, message);
        } else if ("tools/call".equals(method)) {
            return handleToolsCall(server, params, message);
        } else {
            return createJsonRpcError(-32601, "Method '" + method + "' not found");
        }
    } catch (Exception e) {
        return createJsonRpcError(-32603, e.getMessage());
    }
}

private Map<String, Object> createMcpInitializeResponse(SdkMcpServer server) {
    // ~20 lines
}

private Map<String, Object> handleToolsList(SdkMcpServer server, Map<String, Object> message) {
    // ~40 lines - iterate tools, convert to JSON
}

private Map<String, Object> handleToolsCall(SdkMcpServer server, Map<String, Object> params, Map<String, Object> message) {
    // ~80 lines - find tool, execute handler, convert result
}
```

---

## Effort Estimate

| Task | Lines | Complexity | Time |
|------|-------|------------|------|
| Control protocol foundation | ~100 | Medium | 4 hours |
| Message router | ~50 | Medium | 2 hours |
| Control request dispatcher | ~50 | Medium | 2 hours |
| Permission callback handler | ~50 | Medium | 3 hours |
| Hook callback handler | ~30 | Medium | 2 hours |
| MCP message bridge | ~200 | High | 8 hours |
| Callback registration | ~40 | Medium | 2 hours |
| Control request/response tracking | ~50 | Medium | 2 hours |
| Testing & debugging | - | High | 6 hours |
| **TOTAL** | **~570 lines** | | **31 hours (~4 days)** |

---

## Priority Recommendations

### CRITICAL (Must implement for feature parity)

1. **Control Protocol Foundation** (4 hours)
   - Callback registry
   - Request tracking
   - Server storage

2. **Message Router** (2 hours)
   - Distinguish control vs SDK messages
   - Route to appropriate handlers

3. **Control Request Handler** (2 hours)
   - Dispatch to hook/permission/MCP handlers

4. **Hook Callback System** (4 hours)
   - Registration with IDs
   - Callback lookup and execution

5. **Permission Callback System** (3 hours)
   - Store callback
   - Handle can_use_tool requests

6. **MCP Server Bridge** (8 hours)
   - JSONRPC routing
   - Tool list/call handlers

### HIGH (Important for robustness)

7. **Control Request/Response Tracking** (2 hours)
   - Request ID generation
   - Response matching
   - Timeout handling

8. **Comprehensive Testing** (6 hours)
   - Test all control flows
   - Test error cases

---

## Current Feature Status (Revised)

| Feature | Previous Estimate | Actual Status | Reason |
|---------|------------------|---------------|--------|
| Type System | 100% | 100% ✅ | Correct |
| Transport Layer | 95% | 95% ✅ | Correct |
| Client API | 95% | 40% ❌ | Missing control protocol |
| Query Function | 100% | 100% ✅ | Correct |
| **Hooks** | **90%** | **0%** ❌ | No callback execution |
| **Permissions** | **90%** | **0%** ❌ | No callback execution |
| **SDK MCP** | **40%** | **0%** ❌ | No message routing |
| MCP Tool Definition | 100% | 100% ✅ | Correct |
| Control Commands | 100% | 60% ⚠️ | No response handling |
| **Overall** | **93%** | **~65%** ❌ | Major gaps found |

---

## Conclusion

The previous 93% feature parity estimate was **overly optimistic** because it didn't account for the missing control protocol implementation.

**Reality:**
- Java SDK has ~65% feature parity
- Missing ~570 lines of critical control protocol code
- 3 major features (hooks, permissions, SDK MCP) are completely broken
- Estimated 4 days of work to reach true feature parity

**The Good News:**
- The architecture is sound
- Type system is complete
- Transport layer is solid
- Once control protocol is added, everything else falls into place

**The Bad News:**
- Without control protocol, several advertised features don't work
- Users trying to use hooks/permissions/SDK MCP will have broken code
- Cannot claim feature parity until this is fixed

**Recommendation:** Do NOT release as v1.0 until control protocol is implemented. Current state is suitable for v0.1 with clear documentation of limitations.
