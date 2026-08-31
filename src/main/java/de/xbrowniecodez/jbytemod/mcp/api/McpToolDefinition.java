package de.xbrowniecodez.jbytemod.mcp.api;

import java.util.Map;
import java.util.Objects;

public record McpToolDefinition(String name, String description, Map<String, Object> inputSchema,
                                boolean readOnly, boolean destructive, boolean idempotent) {
    public McpToolDefinition {
        if (Objects.requireNonNull(name, "name").isBlank()) {
            throw new IllegalArgumentException("Tool name cannot be blank");
        }
        if (Objects.requireNonNull(description, "description").isBlank()) {
            throw new IllegalArgumentException("Tool description cannot be blank");
        }
        inputSchema = Map.copyOf(Objects.requireNonNull(inputSchema, "inputSchema"));
    }
}
