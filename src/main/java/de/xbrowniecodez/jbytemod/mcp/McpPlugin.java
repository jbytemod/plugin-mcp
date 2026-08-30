package de.xbrowniecodez.jbytemod.mcp;

import de.xbrowniecodez.jbytemod.plugin.Plugin;
import org.objectweb.asm.tree.ClassNode;

import javax.swing.JOptionPane;
import java.util.Map;

public final class McpPlugin extends Plugin {
    private static final int DEFAULT_PORT = 8765;

    private McpServer server;

    public McpPlugin() {
        super("MCP Server", getPluginVersion(), "brownie");
    }

    private static String getPluginVersion() {
        String version = McpPlugin.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    @Override
    public void init() {
        startServer();
    }

    @Override
    public void loadFile(Map<String, ClassNode> map) {
    }

    @Override
    public boolean isClickable() {
        return true;
    }

    @Override
    public void menuClick() {
        boolean running = server != null && server.isRunning();
        String message = running
                ? "The JByteMod MCP server is running at:\n" + server.getEndpoint()
                : "The JByteMod MCP server is stopped.";
        String action = running ? "Stop server" : "Start server";
        int choice = JOptionPane.showOptionDialog(getMenu(), message, "JByteMod MCP",
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
                new Object[]{action, "Close"}, "Close");
        if (choice != 0) {
            return;
        }
        if (running) {
            stopServer();
        } else {
            startServer();
        }
    }

    @Override
    public void shutdown() {
        stopServer();
    }

    private void startServer() {
        if (server != null && server.isRunning()) {
            return;
        }
        int port = Integer.getInteger("jbytemod.mcp.port", DEFAULT_PORT);
        try {
            server = new McpServer(getContext(), port);
            server.start();
            getContext().log("MCP server listening at " + server.getEndpoint());
        } catch (Exception exception) {
            server = null;
            getContext().logError("Could not start MCP server on 127.0.0.1:" + port, exception);
        }
    }

    private void stopServer() {
        if (server == null) {
            return;
        }
        server.close();
        server = null;
        getContext().log("MCP server stopped");
    }
}
