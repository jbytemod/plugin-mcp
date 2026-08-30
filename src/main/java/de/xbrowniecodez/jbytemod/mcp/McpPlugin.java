package de.xbrowniecodez.jbytemod.mcp;

import de.xbrowniecodez.jbytemod.plugin.Plugin;
import org.objectweb.asm.tree.ClassNode;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.net.BindException;
import java.util.Map;
import java.util.prefs.Preferences;

public final class McpPlugin extends Plugin {
    private static final int DEFAULT_PORT = 8765;
    private static final String PORT_PROPERTY = "jbytemod.mcp.port";
    private static final String PORT_PREFERENCE = "port";
    private static final String ENABLED_PREFERENCE = "enabled";
    private static final int PORT_SEARCH_LIMIT = 100;
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(McpPlugin.class);

    private McpServer server;
    private int port;
    private boolean enabled;

    public McpPlugin() {
        super("MCP Server", getPluginVersion(), "brownie");
    }

    private static String getPluginVersion() {
        String version = McpPlugin.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    @Override
    public void init() {
        port = loadPort();
        enabled = PREFERENCES.getBoolean(ENABLED_PREFERENCE, true);
        if (enabled) {
            startServer();
        }
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
        JSpinner portInput = new JSpinner(new SpinnerNumberModel(port, 1, 65535, 1));
        JSpinner.NumberEditor portEditor = new JSpinner.NumberEditor(portInput, "#");
        portEditor.getTextField().setColumns(6);
        portInput.setEditor(portEditor);
        JCheckBox enabledInput = new JCheckBox("Run MCP server", enabled);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.anchor = GridBagConstraints.WEST;
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 2;
        constraints.insets = new Insets(0, 0, 10, 0);
        panel.add(new JLabel(running ? "Running at " + server.getEndpoint() : "Server is stopped"), constraints);

        constraints.gridy = 1;
        constraints.gridwidth = 1;
        constraints.insets = new Insets(0, 0, 8, 8);
        panel.add(new JLabel("Preferred port:"), constraints);

        constraints.gridx = 1;
        constraints.insets = new Insets(0, 0, 8, 0);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;
        panel.add(portInput, constraints);

        constraints.gridx = 0;
        constraints.gridy = 2;
        constraints.gridwidth = 2;
        constraints.insets = new Insets(0, 0, 0, 0);
        constraints.fill = GridBagConstraints.NONE;
        constraints.weightx = 0;
        panel.add(enabledInput, constraints);

        int choice = JOptionPane.showConfirmDialog(
                SwingUtilities.getWindowAncestor(getMenu()), panel, "MCP Server",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }

        int newPort = (int) portInput.getValue();
        boolean newEnabled = enabledInput.isSelected();
        if (running && (!newEnabled || newPort != port)) {
            stopServer();
        }

        port = newPort;
        enabled = newEnabled;
        PREFERENCES.putInt(PORT_PREFERENCE, port);
        PREFERENCES.putBoolean(ENABLED_PREFERENCE, enabled);

        if (enabled && (server == null || !server.isRunning())) {
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
        try {
            server = bindServer();
            server.start();
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
                McpServer candidateServer = new McpServer(getContext(), candidate);
                if (candidate != port) {
                    getContext().log("MCP port " + port + " is in use; using " + candidate + " instead");
                }
                return candidateServer;
            } catch (BindException ignored) {
            }
        }

        McpServer candidateServer = new McpServer(getContext(), 0);
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
        getContext().log("MCP server stopped");
    }

    private static int loadPort() {
        int configured = Integer.getInteger(PORT_PROPERTY,
                PREFERENCES.getInt(PORT_PREFERENCE, DEFAULT_PORT));
        return configured >= 1 && configured <= 65535 ? configured : DEFAULT_PORT;
    }
}
