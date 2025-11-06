package com.anthropic.examples;

import com.anthropic.claude.sdk.client.ClaudeAgentOptions;
import com.anthropic.claude.sdk.client.ClaudeSDKClient;
import com.anthropic.claude.sdk.mcp.SdkMcpServer;
import com.anthropic.claude.sdk.mcp.SdkMcpTool;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Example demonstrating SDK MCP server usage.
 *
 * This example shows how to:
 * 1. Create custom tools using SdkMcpTool
 * 2. Group tools into an SDK MCP server
 * 3. Define input schemas for tools
 * 4. Handle tool execution with async operations
 * 5. Return results in MCP format
 */
public class SdkMcpServerExample {

    // Example input class for the calculator tool
    public static class CalculatorInput {
        public String operation;
        public double a;
        public double b;

        // Getters and setters
        public String getOperation() { return operation; }
        public void setOperation(String operation) { this.operation = operation; }
        public double getA() { return a; }
        public void setA(double a) { this.a = a; }
        public double getB() { return b; }
        public void setB(double b) { this.b = b; }
    }

    // Example input class for the database tool
    public static class DatabaseQueryInput {
        public String query;
        public int limit;

        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public int getLimit() { return limit; }
        public void setLimit(int limit) { this.limit = limit; }
    }

    public static void main(String[] args) {
        // Create Tool 1: Calculator
        SdkMcpTool<CalculatorInput> calculatorTool = SdkMcpTool.<CalculatorInput>builder(
                "calculate",
                "Performs basic mathematical operations (add, subtract, multiply, divide)"
        )
        .inputClass(CalculatorInput.class)
        .inputSchema(createCalculatorSchema())
        .handler(input -> {
            // Async calculation
            return CompletableFuture.supplyAsync(() -> {
                try {
                    double result;
                    switch (input.getOperation()) {
                        case "add":
                            result = input.getA() + input.getB();
                            break;
                        case "subtract":
                            result = input.getA() - input.getB();
                            break;
                        case "multiply":
                            result = input.getA() * input.getB();
                            break;
                        case "divide":
                            if (input.getB() == 0) {
                                Map<String, Object> error = new HashMap<>();
                                error.put("content", "Cannot divide by zero");
                                error.put("isError", true);
                                return error;
                            }
                            result = input.getA() / input.getB();
                            break;
                        default:
                            Map<String, Object> error = new HashMap<>();
                            error.put("content", "Unknown operation: " + input.getOperation());
                            error.put("isError", true);
                            return error;
                    }

                    Map<String, Object> response = new HashMap<>();
                    response.put("content", String.format("%s %s %s = %s",
                            input.getA(), input.getOperation(), input.getB(), result));
                    response.put("result", result);
                    return response;

                } catch (Exception e) {
                    Map<String, Object> error = new HashMap<>();
                    error.put("content", "Error: " + e.getMessage());
                    error.put("isError", true);
                    return error;
                }
            });
        })
        .build();

        // Create Tool 2: Database Query (simulated)
        SdkMcpTool<DatabaseQueryInput> databaseTool = SdkMcpTool.<DatabaseQueryInput>builder(
                "query_database",
                "Queries a simulated database and returns results"
        )
        .inputClass(DatabaseQueryInput.class)
        .inputSchema(createDatabaseSchema())
        .handler(input -> {
            return CompletableFuture.supplyAsync(() -> {
                // Simulate database query
                List<Map<String, Object>> results = new ArrayList<>();

                // Mock data based on query
                String query = input.getQuery().toLowerCase();
                if (query.contains("users")) {
                    for (int i = 1; i <= Math.min(input.getLimit(), 3); i++) {
                        Map<String, Object> row = new HashMap<>();
                        row.put("id", i);
                        row.put("name", "User " + i);
                        row.put("email", "user" + i + "@example.com");
                        results.add(row);
                    }
                } else if (query.contains("products")) {
                    for (int i = 1; i <= Math.min(input.getLimit(), 3); i++) {
                        Map<String, Object> row = new HashMap<>();
                        row.put("id", i);
                        row.put("name", "Product " + i);
                        row.put("price", 10.0 * i);
                        results.add(row);
                    }
                } else {
                    Map<String, Object> error = new HashMap<>();
                    error.put("content", "Unknown table in query: " + input.getQuery());
                    error.put("isError", true);
                    return error;
                }

                Map<String, Object> response = new HashMap<>();
                response.put("content", String.format("Found %d results", results.size()));
                response.put("results", results);
                response.put("query", input.getQuery());
                return response;
            });
        })
        .build();

        // Create Tool 3: Simple greeting tool (no input class)
        SdkMcpTool<Map<String, Object>> greetingTool = SdkMcpTool.<Map<String, Object>>builder(
                "greet",
                "Returns a greeting message"
        )
        .handler(input -> {
            String name = input != null ? (String) input.get("name") : "World";
            Map<String, Object> response = new HashMap<>();
            response.put("content", "Hello, " + (name != null ? name : "World") + "!");
            return CompletableFuture.completedFuture(response);
        })
        .build();

        // Create SDK MCP Server with all tools
        List<SdkMcpTool<?>> tools = Arrays.asList(calculatorTool, databaseTool, greetingTool);
        SdkMcpServer myServer = SdkMcpServer.create("my-tools", "1.0.0", tools);

        // Configure MCP servers in options
        Map<String, Object> mcpServers = new HashMap<>();
        mcpServers.put("my-tools", myServer);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .mcpServers(mcpServers)
                .build();

        try (ClaudeSDKClient client = new ClaudeSDKClient(options, null)) {
            System.out.println("Starting client with SDK MCP server...\n");
            System.out.println("Available tools:");
            System.out.println("  - calculate: Perform math operations");
            System.out.println("  - query_database: Query simulated database");
            System.out.println("  - greet: Get a greeting\n");

            // Connect and start
            client.connect("Calculate 10 + 5, then query the users table with limit 2");

            // Wait for processing
            Thread.sleep(15000);

            System.out.println("\n=== Example completed ===");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Helper method to create calculator input schema
    private static Map<String, Object> createCalculatorSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> operation = new HashMap<>();
        operation.put("type", "string");
        operation.put("enum", Arrays.asList("add", "subtract", "multiply", "divide"));
        operation.put("description", "The mathematical operation to perform");
        properties.put("operation", operation);

        Map<String, Object> a = new HashMap<>();
        a.put("type", "number");
        a.put("description", "The first operand");
        properties.put("a", a);

        Map<String, Object> b = new HashMap<>();
        b.put("type", "number");
        b.put("description", "The second operand");
        properties.put("b", b);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("operation", "a", "b"));

        return schema;
    }

    // Helper method to create database input schema
    private static Map<String, Object> createDatabaseSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> query = new HashMap<>();
        query.put("type", "string");
        query.put("description", "The SQL query to execute");
        properties.put("query", query);

        Map<String, Object> limit = new HashMap<>();
        limit.put("type", "integer");
        limit.put("description", "Maximum number of results to return");
        limit.put("default", 10);
        properties.put("limit", limit);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("query"));

        return schema;
    }
}
