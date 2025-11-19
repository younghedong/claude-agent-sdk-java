package com.anthropic.claude.sdk.types.permissions;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Permission update payload returned to the CLI.
 */
@Data
@AllArgsConstructor
public final class PermissionUpdate {
    private final String type;
    private final Map<String, Object> payload;

    public Map<String, Object> toMap() {
        Map<String, Object> result = new HashMap<>();
        if (type != null) {
            result.put("type", type);
        }
        if (payload != null) {
            result.putAll(payload);
        }
        return result;
    }
}
