package de.xbrowniecodez.jbytemod.mcp.api;

import java.util.List;
import java.util.Map;

public interface McpToolProvider {
    List<McpToolDefinition> tools();

    Object call(String toolName, Map<String, Object> arguments) throws Exception;
}
