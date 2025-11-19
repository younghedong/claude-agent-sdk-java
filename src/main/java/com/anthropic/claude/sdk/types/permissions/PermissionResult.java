package com.anthropic.claude.sdk.types.permissions;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Result of a permission check.
 */
public interface PermissionResult {

    static Allow allow() {
        return new Allow(null, Collections.emptyList());
    }

    static Allow allow(Map<String, Object> updatedInput) {
        return new Allow(updatedInput, Collections.emptyList());
    }

    static Allow allow(Map<String, Object> updatedInput, List<PermissionUpdate> updates) {
        return new Allow(updatedInput, updates);
    }

    static Deny deny(String message) {
        return new Deny(message, Boolean.FALSE);
    }

    static Deny deny(String message, boolean interrupt) {
        return new Deny(message, interrupt);
    }

    /**
     * Permission granted.
     */
    @Data
    @AllArgsConstructor
    final class Allow implements PermissionResult {
        private final Map<String, Object> updatedInput;
        private final List<PermissionUpdate> updatedPermissions;
    }

    /**
     * Permission denied.
     */
    @Data
    @AllArgsConstructor
    final class Deny implements PermissionResult {
        private final String message;
        private final Boolean interrupt;
    }
}
