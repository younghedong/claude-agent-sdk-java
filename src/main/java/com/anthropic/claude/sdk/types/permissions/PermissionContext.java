package com.anthropic.claude.sdk.types.permissions;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Context provided to permission callbacks.
 */
@Data
@AllArgsConstructor
public final class PermissionContext {
    private final Object signal;                       // For cancellation support (future)
    private final List<Map<String, Object>> suggestions; // Suggested permission responses
}
