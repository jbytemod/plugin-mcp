package de.xbrowniecodez.jbytemod.mcp;

import de.xbrowniecodez.jbytemod.mcp.api.McpToolProvider;
import de.xbrowniecodez.jbytemod.plugin.Plugin;
import org.objectweb.asm.tree.ClassNode;

import javax.swing.SwingUtilities;
import java.awt.Window;
import java.net.BindException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

public final class McpPlugin extends Plugin {
    private static final int DEFAULT_PORT = 8765;
    private static final String PORT_PROPERTY = "jbytemod.mcp.port";
    private static final String PORT_PREFERENCE = "port";
    private static final String ENABLED_PREFERENCE = "enabled";
    private static final int PORT_SEARCH_LIMIT = 100;
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(McpPlugin.class);
    private static final CopyOnWriteArrayList<McpToolProvider> TOOL_PROVIDERS = new CopyOnWriteArrayList<>();

    private final McpActivityLog activityLog = new McpActivityLog();
    private McpServer server;
    private McpWorkspace workspace;
    private McpActivityDialog activityDialog;
    private int port;
    private boolean enabled;

    public McpPlugin() {
        super("MCP Server", getPluginVersion(), "brownie");
    }

    public static AutoCloseable registerToolProvider(McpToolProvider provider) {
        McpToolProvider registered = Objects.requireNonNull(provider, "provider");
        TOOL_PROVIDERS.addIfAbsent(registered);
        return () -> TOOL_PROVIDERS.remove(registered);
    }

    static List<McpToolProvider> getToolProviders() {
        return List.copyOf(TOOL_PROVIDERS);
    }

    private static String getPluginVersion() {
        String version = McpPlugin.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    @Override
    public void init() {
        port = loadPort();
        enabled = PREFERENCES.getBoolean(ENABLED_PREFERENCE, true);
        workspace = new McpWorkspace(getContext());
        workspace.reset(getCurrentFile());
        if (enabled) {
            startServer();
        }
    }

    @Override
    public void loadFile(Map<String, ClassNode> map) {
        if (workspace != null) {
            workspace.reset(map);
        }
    }

    @Override
    public boolean isClickable() {
        return true;
    }

    @Override
    public void menuClick() {
        if (activityDialog != null && activityDialog.isDisplayable()) {
            activityDialog.setVisible(true);
            activityDialog.toFront();
            return;
        }
        Window owner = SwingUtilities.getWindowAncestor(getMenu());
        activityDialog = new McpActivityDialog(owner, this);
        activityDialog.setVisible(true);
    }

    @Override
    public void shutdown() {
        if (activityDialog != null) {
            activityDialog.dispose();
            activityDialog = null;
        }
        stopServer();
    }

    void configureServer(int newPort, boolean newEnabled, boolean forceRestart) {
        boolean running = isServerRunning();
        if (running && (!newEnabled || newPort != port || forceRestart)) {
            stopServer();
        }

        port = newPort;
        enabled = newEnabled;
        PREFERENCES.putInt(PORT_PREFERENCE, port);
        PREFERENCES.putBoolean(ENABLED_PREFERENCE, enabled);
        try {
            PREFERENCES.flush();
        } catch (BackingStoreException exception) {
            getContext().logError("Could not save MCP server settings", exception);
        }

        if (enabled && !isServerRunning()) {
            startServer();
        }
    }

    boolean isServerRunning() {
        return server != null && server.isRunning();
    }

    boolean isServerEnabled() {
        return enabled;
    }

    int getPreferredPort() {
        return port;
    }

    String getServerEndpoint() {
        return isServerRunning() ? server.getEndpoint() : "";
    }

    McpActivityLog getActivityLog() {
        return activityLog;
    }

    int getPendingEditCount() {
        return workspace == null ? 0 : workspace.changes().size();
    }

    private void startServer() {
        if (server != null && server.isRunning()) {
            return;
        }
        try {
            server = bindServer();
            server.start();
            activityLog.record("JByteMod", "Server started", "OK", 0);
            getContext().log("MCP server listening at " + server.getEndpoint());
        } catch (Exception exception) {
            server = null;
            getContext().logError("Could not start MCP server on 127.0.0.1:" + port, exception);
        }
    }

    private McpServer bindServer() throws Exception {
        int lastSequentialPort = Math.min(65535, port + PORT_SEARCH_LIMIT - 1);
        for (int candidate = port; candidate <= lastSequentialPort; candidate++) {
            try {
                McpServer candidateServer = new McpServer(getContext(), candidate, activityLog, workspace);
                if (candidate != port) {
                    getContext().log("MCP port " + port + " is in use; using " + candidate + " instead");
                }
                return candidateServer;
            } catch (BindException ignored) {
            }
        }

        McpServer candidateServer = new McpServer(getContext(), 0, activityLog, workspace);
        getContext().log("No free MCP port found near " + port + "; using "
                + candidateServer.getPort() + " instead");
        return candidateServer;
    }

    private void stopServer() {
        if (server == null) {
            return;
        }
        server.close();
        server = null;
        activityLog.record("JByteMod", "Server stopped", "OK", 0);
        getContext().log("MCP server stopped");
    }

    private static int loadPort() {
        int configured = Integer.getInteger(PORT_PROPERTY,
                PREFERENCES.getInt(PORT_PREFERENCE, DEFAULT_PORT));
        return configured >= 1 && configured <= 65535 ? configured : DEFAULT_PORT;
    }
}
