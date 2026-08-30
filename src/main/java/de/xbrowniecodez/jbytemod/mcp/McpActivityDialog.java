package de.xbrowniecodez.jbytemod.mcp;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class McpActivityDialog extends JDialog {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final McpPlugin plugin;
    private final JLabel statusValue = new JLabel();
    private final JTextField endpointValue = new JTextField();
    private final JLabel editsValue = new JLabel();
    private final JCheckBox runServer = new JCheckBox("Run MCP server");
    private final JSpinner portInput;
    private final DefaultTableModel activityModel = tableModel("Time", "Client", "Request", "Result", "Duration");
    private final DefaultTableModel clientModel = tableModel("Client", "Version", "Last seen", "Requests");
    private final Timer refreshTimer;

    McpActivityDialog(Window owner, McpPlugin plugin) {
        super(owner, "MCP Activity", ModalityType.MODELESS);
        this.plugin = plugin;
        this.portInput = new JSpinner(new SpinnerNumberModel(plugin.getPreferredPort(), 1, 65535, 1));
        this.refreshTimer = new Timer(500, event -> refresh());

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setContentPane(createContent());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                refreshTimer.stop();
            }
        });

        setMinimumSize(new Dimension(680, 400));
        setPreferredSize(new Dimension(780, 480));
        pack();
        setLocationRelativeTo(owner);
        refresh();
        refreshTimer.start();
    }

    private JPanel createContent() {
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(14, 14, 12, 14));

        JPanel header = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(0, 0, 7, 10);

        JLabel title = new JLabel("MCP server activity");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 1));
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 4;
        header.add(title, constraints);

        constraints.gridy = 1;
        constraints.gridwidth = 1;
        header.add(new JLabel("Status:"), constraints);
        constraints.gridx = 1;
        constraints.gridwidth = 3;
        constraints.weightx = 1;
        header.add(statusValue, constraints);

        constraints.gridx = 0;
        constraints.gridy = 2;
        constraints.gridwidth = 1;
        constraints.weightx = 0;
        header.add(new JLabel("Endpoint:"), constraints);
        endpointValue.setEditable(false);
        endpointValue.setBorder(null);
        endpointValue.setOpaque(false);
        constraints.gridx = 1;
        constraints.gridwidth = 3;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;
        header.add(endpointValue, constraints);

        constraints.gridx = 0;
        constraints.gridy = 3;
        constraints.gridwidth = 1;
        constraints.fill = GridBagConstraints.NONE;
        constraints.weightx = 0;
        header.add(new JLabel("Pending edits:"), constraints);
        constraints.gridx = 1;
        constraints.gridwidth = 3;
        header.add(editsValue, constraints);

        JSpinner.NumberEditor portEditor = new JSpinner.NumberEditor(portInput, "#");
        portEditor.getTextField().setColumns(6);
        portInput.setEditor(portEditor);
        runServer.addActionListener(event -> plugin.configureServer(
                (int) portInput.getValue(), runServer.isSelected(), false));
        JButton restart = new JButton("Use Port");
        restart.addActionListener(event -> plugin.configureServer(
                (int) portInput.getValue(), runServer.isSelected(), true));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.add(runServer);
        controls.add(new JLabel("Preferred port:"));
        controls.add(portInput);
        controls.add(restart);
        constraints.gridx = 0;
        constraints.gridy = 4;
        constraints.gridwidth = 4;
        constraints.insets = new Insets(3, -5, 0, 0);
        header.add(controls, constraints);
        content.add(header, BorderLayout.NORTH);

        JTable activityTable = createTable(activityModel);
        activityTable.getColumnModel().getColumn(0).setPreferredWidth(65);
        activityTable.getColumnModel().getColumn(1).setPreferredWidth(150);
        activityTable.getColumnModel().getColumn(2).setPreferredWidth(240);
        activityTable.getColumnModel().getColumn(3).setPreferredWidth(75);
        activityTable.getColumnModel().getColumn(4).setPreferredWidth(70);

        JTable clientTable = createTable(clientModel);
        clientTable.getColumnModel().getColumn(0).setPreferredWidth(240);
        clientTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        clientTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        clientTable.getColumnModel().getColumn(3).setPreferredWidth(80);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Activity", new JScrollPane(activityTable));
        tabs.addTab("Clients", new JScrollPane(clientTable));
        content.add(tabs, BorderLayout.CENTER);

        JButton clear = new JButton("Clear Activity");
        clear.addActionListener(event -> {
            plugin.getActivityLog().clearActivities();
            refresh();
        });
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(clear);
        buttons.add(close);
        content.add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(close);
        return content;
    }

    private void refresh() {
        boolean running = plugin.isServerRunning();
        statusValue.setText(running ? "Running" : "Stopped");
        endpointValue.setText(running ? plugin.getServerEndpoint() : "-");
        editsValue.setText(Integer.toString(plugin.getActivityLog().pendingEdits()));
        runServer.setSelected(plugin.isServerEnabled());

        activityModel.setRowCount(0);
        for (McpActivityLog.Activity activity : plugin.getActivityLog().activities()) {
            activityModel.addRow(new Object[]{
                    TIME_FORMAT.format(activity.timestamp()), activity.client(), activity.action(), activity.result(),
                    activity.durationMillis() + " ms"
            });
        }

        clientModel.setRowCount(0);
        for (McpActivityLog.Client client : plugin.getActivityLog().clients()) {
            clientModel.addRow(new Object[]{
                    client.name(), client.version(), TIME_FORMAT.format(client.lastSeen()), client.requests()
            });
        }
    }

    private static JTable createTable(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        return table;
    }

    private static DefaultTableModel tableModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }
}
