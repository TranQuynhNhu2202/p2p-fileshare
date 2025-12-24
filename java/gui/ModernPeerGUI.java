package gui;

import peer.*;
import tracker.FileInfo;
import utils.NetworkUtils;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.util.List;
import java.util.Map; // Added for Map import

/**
 * Giao diện hiện đại cho P2P File Sharing
 * Sử dụng FlatLaf Look and Feel + Custom styling
 */
public class ModernPeerGUI extends JFrame {

    // Màu sắc theme
    private static final Color PRIMARY = new Color(99, 102, 241); // Indigo
    private static final Color PRIMARY_DARK = new Color(79, 70, 229);
    private static final Color SUCCESS = new Color(34, 197, 94); // Green
    private static final Color DANGER = new Color(239, 68, 68); // Red
    private static final Color WARNING = new Color(245, 158, 11); // Amber
    private static final Color BG_DARK = new Color(17, 24, 39); // Dark background
    private static final Color BG_CARD = new Color(31, 41, 55); // Card background
    private static final Color BG_INPUT = new Color(55, 65, 81); // Input background
    private static final Color TEXT_PRIMARY = new Color(243, 244, 246);
    private static final Color TEXT_SECONDARY = new Color(156, 163, 175);
    private static final Color BORDER_COLOR = new Color(75, 85, 99);

    private Peer peer;
    private MultiSourceDownloader downloader;

    // Components
    private JTextField trackerField;
    private JLabel statusLabel;
    private JLabel ipLabel; // Hiển thị IP LAN
    private JButton connectBtn;
    private JTextField searchField;
    private JTable fileTable;
    private DefaultTableModel tableModel;
    private JTable downloadTable;
    private DefaultTableModel downloadTableModel;
    private JTable sharedTable;
    private DefaultTableModel sharedTableModel;
    private JTextArea logArea;

    // Config
    private String trackerHost = "localhost";
    private int trackerPort = 5000;
    private int peerPort;

    // Settings
    private boolean isAskForDownloadPath = false;
    private String currentDownloadFolder = "downloads"; // Default folder

    public ModernPeerGUI(int peerPort) {
        this.peerPort = peerPort;
        setupLookAndFeel();
        initUI();
    }

    private void setupLookAndFeel() {
        try {
            // Sử dụng FlatLaf Dark theme
            UIManager.setLookAndFeel("com.formdev.flatlaf.FlatDarkLaf");

            // Custom UI properties
            UIManager.put("Button.arc", 10);
            UIManager.put("Component.arc", 10);
            UIManager.put("ProgressBar.arc", 10);
            UIManager.put("TextComponent.arc", 8);
            UIManager.put("ScrollBar.thumbArc", 999);
            UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
            UIManager.put("Table.showHorizontalLines", true);
            UIManager.put("Table.showVerticalLines", false);
            UIManager.put("TableHeader.separatorColor", BORDER_COLOR);
            UIManager.put("Table.gridColor", new Color(55, 65, 81));

        } catch (Exception e) {
            // Fallback
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ex) {
            }
        }
    }

    private void initUI() {
        setTitle("⚡ P2P File Sharing - Port " + peerPort);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 750);
        setLocationRelativeTo(null);
        setBackground(BG_DARK);

        // Main container
        JPanel mainPanel = new JPanel(new BorderLayout(0, 0));
        mainPanel.setBackground(BG_DARK);
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        // Header
        mainPanel.add(createHeader(), BorderLayout.NORTH);

        // Content
        mainPanel.add(createContent(), BorderLayout.CENTER);

        // Footer (Log)
        mainPanel.add(createFooter(), BorderLayout.SOUTH);

        add(mainPanel);

        // Window events
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (peer != null && peer.isRunning()) {
                    peer.stop();
                }
                if (downloader != null) {
                    downloader.shutdown();
                }
            }
        });
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(15, 0));
        header.setBackground(BG_DARK);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 15, 0));

        // Logo & Title
        JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        titlePanel.setBackground(BG_DARK);

        JLabel logo = new JLabel("[P2P]");
        logo.setFont(new Font("Segoe UI", Font.BOLD, 24));
        titlePanel.add(logo);

        JLabel title = new JLabel("P2P File Sharing");
        title.setFont(new Font("Segoe UI", Font.BOLD, 22));
        title.setForeground(TEXT_PRIMARY);
        titlePanel.add(title);

        // Hiển thị IP LAN
        String localIP = NetworkUtils.getLocalIPAddress();
        ipLabel = new JLabel("  |  IP: " + localIP);
        ipLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        ipLabel.setForeground(new Color(34, 197, 94)); // Green
        titlePanel.add(ipLabel);

        header.add(titlePanel, BorderLayout.WEST);

        // Header Action Panel (Settings)
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actionPanel.setOpaque(false);

        JButton settingsBtn = createSmallButton("Cai Dat", BG_INPUT);
        settingsBtn.setPreferredSize(new Dimension(80, 30));
        settingsBtn.addActionListener(e -> showSettingsDialog());
        actionPanel.add(settingsBtn);

        header.add(actionPanel, BorderLayout.EAST);

        // Connection Bar
        JPanel connPanel = createRoundedPanel(BG_CARD);
        connPanel.setLayout(new FlowLayout(FlowLayout.RIGHT, 10, 8));

        JLabel trackerLabel = new JLabel("Tracker:");
        trackerLabel.setForeground(TEXT_SECONDARY);
        connPanel.add(trackerLabel);

        trackerField = createStyledTextField("localhost:5000", 15);
        connPanel.add(trackerField);

        connectBtn = createStyledButton("Ket noi", PRIMARY);
        connectBtn.addActionListener(e -> toggleConnection());
        connPanel.add(connectBtn);

        statusLabel = new JLabel("● Offline");
        statusLabel.setForeground(TEXT_SECONDARY);
        statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 15, 0, 5));
        connPanel.add(statusLabel);

        // The original BorderLayout.EAST for connPanel is now replaced by actionPanel.
        // To keep connPanel, we need to wrap it or adjust the layout.
        // For now, I'll assume the user wants the settings button to replace the
        // connection panel in BorderLayout.EAST
        // or that the connection panel will be placed elsewhere.
        // Based on the diff, it seems the user intends to put the settings button in
        // BorderLayout.EAST,
        // and the connection panel is then added to the header, but the
        // BorderLayout.EAST can only hold one component.
        // I will put the connection panel in the center of the header, or combine it
        // with the actionPanel.
        // Let's combine them into a single panel for BorderLayout.EAST.

        JPanel rightHeaderPanel = new JPanel();
        rightHeaderPanel.setLayout(new BoxLayout(rightHeaderPanel, BoxLayout.Y_AXIS));
        rightHeaderPanel.setOpaque(false);
        rightHeaderPanel.add(actionPanel);
        rightHeaderPanel.add(connPanel); // Add connection panel below settings button

        header.add(rightHeaderPanel, BorderLayout.EAST); // This will replace the previous actionPanel add.

        return header;
    }

    private JPanel createContent() {
        JPanel content = new JPanel(new BorderLayout(15, 15));
        content.setBackground(BG_DARK);

        // Left: File browser + Downloads
        JPanel leftPanel = new JPanel(new BorderLayout(0, 15));
        leftPanel.setBackground(BG_DARK);
        leftPanel.setPreferredSize(new Dimension(700, 0));

        leftPanel.add(createFileBrowserCard(), BorderLayout.CENTER);
        leftPanel.add(createDownloadsCard(), BorderLayout.SOUTH);

        content.add(leftPanel, BorderLayout.CENTER);

        // Right: Shared files
        content.add(createSharedFilesCard(), BorderLayout.EAST);

        return content;
    }

    private JPanel createFileBrowserCard() {
        JPanel card = createCard("Files tren mang");
        card.setLayout(new BorderLayout(0, 10));

        // Search bar
        JPanel searchPanel = new JPanel(new BorderLayout(10, 0));
        searchPanel.setBackground(BG_CARD);

        searchField = createStyledTextField("Tìm kiếm file...", 25);
        searchField.addActionListener(e -> searchFiles());
        searchPanel.add(searchField, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        btnPanel.setBackground(BG_CARD);

        JButton searchBtn = createStyledButton("Tim", PRIMARY);
        searchBtn.addActionListener(e -> searchFiles());
        btnPanel.add(searchBtn);

        JButton refreshBtn = createStyledButton("Refresh", BG_INPUT);
        refreshBtn.setToolTipText("Làm mới danh sách");
        refreshBtn.addActionListener(e -> refreshFileList());
        btnPanel.add(refreshBtn);

        searchPanel.add(btnPanel, BorderLayout.EAST);
        card.add(searchPanel, BorderLayout.NORTH);

        // File table
        String[] columns = { "Tên file", "Kích thước", "Seeds", "Nguồn", "Hash" };
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        fileTable = createStyledTable(tableModel);
        fileTable.getColumnModel().getColumn(0).setPreferredWidth(200);
        fileTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        fileTable.getColumnModel().getColumn(2).setPreferredWidth(50);
        fileTable.getColumnModel().getColumn(3).setPreferredWidth(120);
        fileTable.getColumnModel().getColumn(4).setPreferredWidth(100);

        JScrollPane scrollPane = new JScrollPane(fileTable);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(BG_CARD);
        card.add(scrollPane, BorderLayout.CENTER);

        // Download button
        JPanel downloadActionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT)); // Renamed to avoid conflict
        downloadActionPanel.setBackground(BG_CARD);

        JButton downloadBtn = createStyledButton("Tai xuong", SUCCESS);
        downloadBtn.addActionListener(e -> downloadSelectedFile());
        downloadActionPanel.add(downloadBtn);

        JButton multiDownloadBtn = createStyledButton("Multi-source", PRIMARY);
        multiDownloadBtn.setToolTipText("Tải từ nhiều nguồn cùng lúc");
        multiDownloadBtn.addActionListener(e -> downloadMultiSource());
        downloadActionPanel.add(multiDownloadBtn);

        card.add(downloadActionPanel, BorderLayout.SOUTH);

        return card;
    }

    private JPanel createDownloadsCard() {
        JPanel card = createCard("Dang tai");
        card.setLayout(new BorderLayout(0, 10));
        card.setPreferredSize(new Dimension(0, 180));

        String[] columns = { "File", "Tiến trình", "Tốc độ", "Nguồn", "Trạng thái" };
        downloadTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        downloadTable = createStyledTable(downloadTableModel);

        // Custom renderer cho progress
        downloadTable.getColumnModel().getColumn(1).setCellRenderer(new ProgressBarRenderer());

        JScrollPane scrollPane = new JScrollPane(downloadTable);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(BG_CARD);
        card.add(scrollPane, BorderLayout.CENTER);

        // Control buttons - 2 hàng
        JPanel controlContainer = new JPanel(new GridLayout(2, 1, 0, 5));
        controlContainer.setBackground(BG_CARD);

        // Hàng 1: Pause, Resume, Cancel
        JPanel controlRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        controlRow1.setBackground(BG_CARD);

        JButton pauseBtn = createSmallButton("Tam dung", WARNING);
        pauseBtn.addActionListener(e -> pauseDownload());
        controlRow1.add(pauseBtn);

        JButton resumeBtn = createSmallButton("Tiep tuc", SUCCESS);
        resumeBtn.addActionListener(e -> resumeDownload());
        controlRow1.add(resumeBtn);

        JButton cancelBtn = createSmallButton("Huy", DANGER);
        cancelBtn.addActionListener(e -> cancelDownload());
        controlRow1.add(cancelBtn);

        // Hàng 2: Delete và Clear completed
        JPanel controlRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        controlRow2.setBackground(BG_CARD);

        JButton deleteDownloadBtn = createSmallButton("Xoa", DANGER);
        deleteDownloadBtn.addActionListener(e -> deleteDownload());
        controlRow2.add(deleteDownloadBtn);

        JButton clearBtn = createSmallButton("Xoa hoan thanh", BG_INPUT);
        clearBtn.addActionListener(e -> clearCompletedDownloads());
        controlRow2.add(clearBtn);

        controlContainer.add(controlRow1);
        controlContainer.add(controlRow2);

        card.add(controlContainer, BorderLayout.SOUTH);

        return card;
    }

    private JPanel createSharedFilesCard() {
        JPanel card = createCard("Dang chia se");
        card.setLayout(new BorderLayout(0, 10));
        card.setPreferredSize(new Dimension(320, 0));

        // Added "Trang thai" column
        String[] columns = { "Tên file", "Kích thước", "Trạng thái" };
        sharedTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        sharedTable = createStyledTable(sharedTableModel);

        JScrollPane scrollPane = new JScrollPane(sharedTable);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(BG_CARD);
        card.add(scrollPane, BorderLayout.CENTER);

        // Buttons - 2 hàng
        JPanel btnContainer = new JPanel(new GridLayout(2, 1, 5, 5)); // Adjusted vertical gap
        btnContainer.setBackground(BG_CARD);

        // Hàng 1: Thêm và Mở thư mục
        JPanel btnRow1 = new JPanel(new GridLayout(1, 2, 5, 0));
        btnRow1.setBackground(BG_CARD);

        JButton addBtn = createStyledButton("Them file", PRIMARY);
        addBtn.addActionListener(e -> shareNewFile());
        btnRow1.add(addBtn);

        JButton folderBtn = createStyledButton("Mo thu muc", BG_INPUT);
        folderBtn.addActionListener(e -> openSharedFolder());
        btnRow1.add(folderBtn);

        // Hàng 2: Xóa file
        JPanel btnRow2 = new JPanel(new GridLayout(1, 2, 5, 0));
        btnRow2.setBackground(BG_CARD);

        JButton deleteBtn = createStyledButton("Xử lý file", DANGER); // Renamed from "Xoa file"
        deleteBtn.addActionListener(e -> handleDeleteAction());
        btnRow2.add(deleteBtn);

        JButton refreshSharedBtn = createStyledButton("Lam moi", BG_INPUT);
        refreshSharedBtn.addActionListener(e -> refreshSharedFiles());
        btnRow2.add(refreshSharedBtn);

        btnContainer.add(btnRow1);
        btnContainer.add(btnRow2);

        card.add(btnContainer, BorderLayout.SOUTH);

        return card;
    }

    private JPanel createFooter() {
        JPanel footer = createCard("📋 Log hoạt động");
        footer.setLayout(new BorderLayout());
        footer.setPreferredSize(new Dimension(0, 120));

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("JetBrains Mono", Font.PLAIN, 11));
        logArea.setBackground(new Color(17, 24, 39));
        logArea.setForeground(new Color(134, 239, 172));
        logArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        footer.add(scrollPane, BorderLayout.CENTER);

        return footer;
    }

    // ==================== HELPER METHODS ====================

    private JPanel createCard(String title) {
        JPanel card = createRoundedPanel(BG_CARD);
        card.setLayout(new BorderLayout(0, 10));
        card.setBorder(BorderFactory.createCompoundBorder(
                new RoundedBorder(12, BORDER_COLOR),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)));

        if (title != null) {
            JLabel titleLabel = new JLabel(title);
            titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
            titleLabel.setForeground(TEXT_PRIMARY);
            titleLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
            card.add(titleLabel, BorderLayout.NORTH);
        }

        return card;
    }

    private JPanel createRoundedPanel(Color bg) {
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 12, 12));
                g2.dispose();
            }
        };
        panel.setBackground(bg);
        panel.setOpaque(false);
        return panel;
    }

    private JTextField createStyledTextField(String placeholder, int columns) {
        JTextField field = new JTextField(columns);
        field.setBackground(BG_INPUT);
        field.setForeground(TEXT_PRIMARY);
        field.setCaretColor(TEXT_PRIMARY);
        field.setBorder(BorderFactory.createCompoundBorder(
                new RoundedBorder(8, BORDER_COLOR),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        field.setToolTipText(placeholder);
        return field;
    }

    private JButton createStyledButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));

        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                btn.setBackground(bg.darker());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                btn.setBackground(bg);
            }
        });

        return btn;
    }

    private JButton createSmallButton(String text, Color bg) {
        JButton btn = createStyledButton(text, bg);
        btn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        btn.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        return btn;
    }

    private JTable createStyledTable(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setBackground(BG_CARD);
        table.setForeground(TEXT_PRIMARY);
        table.setSelectionBackground(PRIMARY);
        table.setSelectionForeground(Color.WHITE);
        table.setRowHeight(35);
        table.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        table.setGridColor(new Color(55, 65, 81));
        table.setShowGrid(true);
        table.setIntercellSpacing(new Dimension(0, 1));

        // Header styling
        JTableHeader header = table.getTableHeader();
        header.setBackground(new Color(55, 65, 81));
        header.setForeground(TEXT_PRIMARY);
        header.setFont(new Font("Segoe UI", Font.BOLD, 12));
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR));

        return table;
    }

    // ==================== EVENT HANDLERS ====================

    private void toggleConnection() {
        if (peer != null && peer.isRunning()) {
            peer.stop();
            peer = null;
            // peer = null; // Duplicate line, removed
            statusLabel.setText("Offline");
            statusLabel.setForeground(TEXT_SECONDARY);
            connectBtn.setText("Ket noi");
            connectBtn.setBackground(PRIMARY);
            log("Đã ngắt kết nối");
            tableModel.setRowCount(0);
            sharedTableModel.setRowCount(0);
        } else {
            try {
                String input = trackerField.getText().trim();

                // ⭐ Xử lý trường hợp chỉ nhập port
                if (!input.contains(":")) {
                    try {
                        int portOnly = Integer.parseInt(input);
                        input = "localhost:" + portOnly;
                        trackerField.setText(input); // Cập nhật lại ô input
                    } catch (NumberFormatException ex) {
                        // Không phải số, giả sử là hostname
                        input = input + ":5000";
                        trackerField.setText(input);
                    }
                }

                String[] parts = input.split(":");
                if (parts.length < 2) {
                    showError(
                            "Định dạng không hợp lệ!\nNhập theo format: hostname:port\nVí dụ: localhost:5000 hoặc 192.168.1.10:5000");
                    return;
                }

                trackerHost = parts[0];
                trackerPort = Integer.parseInt(parts[1]);

                log("Đang kết nối đến " + trackerHost + ":" + trackerPort + "...");

                peer = new Peer(peerPort, trackerHost, trackerPort);
                // Apply settings to peer
                if (!currentDownloadFolder.equals("downloads")) {
                    peer.getFileManager().setDownloadFolder(currentDownloadFolder);
                }

                downloader = new MultiSourceDownloader(peer.getPeerID(), peer.getFileManager());
                setupDownloaderCallback();
                setupPeerCallback(); // Setup callback for single-source downloads

                if (peer.start()) {
                    statusLabel.setText("Online");
                    statusLabel.setForeground(SUCCESS);
                    connectBtn.setText("Ngat");
                    connectBtn.setBackground(DANGER);
                    log("Ket noi thanh cong!");
                    log("PeerID: " + peer.getPeerID());
                    log("IP LAN cua ban: " + peer.getLocalIP());
                    refreshFileList();
                    refreshSharedFiles();
                } else {
                    showError(
                            "Không thể kết nối đến Tracker!\n\nKiểm tra:\n1. Tracker Server đang chạy?\n2. IP/Port đúng chưa?\n3. Firewall đã mở port?");
                    peer = null;
                }
            } catch (NumberFormatException e) {
                showError("Port không hợp lệ! Port phải là số.\nVí dụ: localhost:5000");
            } catch (Exception e) {
                showError("Lỗi: " + e.getMessage());
                log("❌ " + e.getMessage());
            }
        }
    }

    private void setupDownloaderCallback() {
        downloader.setCallback(new MultiSourceDownloader.DownloadCallback() {
            @Override
            public void onDownloadStarted(String fileName, int totalSources) {
                SwingUtilities.invokeLater(() -> {
                    downloadTableModel
                            .addRow(new Object[] { fileName, 0, "0 KB/s", totalSources + " nguon", "Dang tai" });
                    log("Bat dau tai " + fileName + " tu " + totalSources + " nguon");
                });
            }

            @Override
            public void onProgress(String fileName, int percent, long downloaded, long total, double speed) {
                SwingUtilities.invokeLater(() -> {
                    updateDownloadRow(fileName, percent, formatSpeed(speed), "Dang tai");
                });
            }

            @Override
            public void onSourceStatus(String sourceIp, int port, String status, int chunks) {
                // Có thể hiển thị chi tiết từng nguồn
            }

            @Override
            public void onCompleted(String fileName) {
                SwingUtilities.invokeLater(() -> {
                    updateDownloadRow(fileName, 100, "-", "Hoan thanh");
                    log("Tai xong: " + fileName);
                    refreshSharedFiles();
                    // refreshSharedFiles(); // Duplicate call, removed
                    // showSuccess("Tải file thành công: " + fileName); // Removed duplicate call

                    // Show dialog with "Open Folder" option
                    File savedFile = peer.getFileManager().getTargetFile(fileName, null);
                    Object[] options = { "Mở thư mục", "OK" };
                    int n = JOptionPane.showOptionDialog(ModernPeerGUI.this,
                            "Tải xuống hoàn tất: " + fileName,
                            "Thông báo",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.INFORMATION_MESSAGE,
                            null,
                            options,
                            options[1]);

                    if (n == 0) { // Open Folder
                        try {
                            Desktop.getDesktop().open(savedFile.getParentFile());
                        } catch (Exception e) {
                            log("Khong the mo thu muc: " + e.getMessage());
                        }
                    }
                });
            }

            @Override
            public void onFailed(String fileName, String error) {
                SwingUtilities.invokeLater(() -> {
                    updateDownloadRow(fileName, -1, "-", "Loi");
                    log("Loi tai " + fileName + ": " + error);
                });
            }

            @Override
            public void onPaused(String fileName, int percent) {
                SwingUtilities.invokeLater(() -> {
                    updateDownloadRow(fileName, percent, "-", "Tam dung");
                    log("Tam dung: " + fileName);
                });
            }

            @Override
            public void onResumed(String fileName, int percent) {
                SwingUtilities.invokeLater(() -> {
                    log("Tiep tuc tai: " + fileName + " (" + percent + "%)");
                });
            }
        });
    }

    private void setupPeerCallback() {
        peer.setCallback(new PeerClient.DownloadCallback() {
            @Override
            public void onDownloadStarted(String fileName, String fromPeer) {
                SwingUtilities.invokeLater(() -> {
                    downloadTableModel.addRow(new Object[] { fileName, 0, "Unknown", fromPeer, "Dang tai" });
                    log("Bat dau tai " + fileName + " tu " + fromPeer);
                });
            }

            @Override
            public void onDownloadProgress(String fileName, int percent, long downloaded, long total, double speed) {
                SwingUtilities.invokeLater(() -> {
                    updateDownloadRow(fileName, percent, formatSpeed(speed), "Dang tai");
                });
            }

            @Override
            public void onDownloadCompleted(String fileName) {
                SwingUtilities.invokeLater(() -> {
                    updateDownloadRow(fileName, 100, "-", "Hoan thanh");
                    log("Tai xong: " + fileName);
                    refreshSharedFiles();
                    // refreshSharedFiles(); // Duplicate call, removed
                    // showSuccess("Tải file thành công: " + fileName); // Removed duplicate call

                    // Show dialog with "Open Folder" option
                    File savedFile = peer.getFileManager().getTargetFile(fileName, null);
                    Object[] options = { "Mở thư mục", "OK" };
                    int n = JOptionPane.showOptionDialog(ModernPeerGUI.this,
                            "Tải xuống hoàn tất: " + fileName,
                            "Thông báo",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.INFORMATION_MESSAGE,
                            null,
                            options,
                            options[1]);

                    if (n == 0) { // Open Folder
                        try {
                            Desktop.getDesktop().open(savedFile.getParentFile());
                        } catch (Exception e) {
                            log("Khong the mo thu muc: " + e.getMessage());
                        }
                    }
                });
            }

            @Override
            public void onDownloadFailed(String fileName, String error) {
                SwingUtilities.invokeLater(() -> {
                    updateDownloadRow(fileName, -1, "-", "Loi");
                    log("Loi tai " + fileName + ": " + error);
                });
            }
        });
    }

    private void updateDownloadRow(String fileName, int percent, String speed, String status) {
        for (int i = 0; i < downloadTableModel.getRowCount(); i++) {
            if (downloadTableModel.getValueAt(i, 0).equals(fileName)) {
                downloadTableModel.setValueAt(percent, i, 1);
                downloadTableModel.setValueAt(speed, i, 2);
                downloadTableModel.setValueAt(status, i, 4);
                return;
            }
        }
    }

    private void searchFiles() {
        if (peer == null)
            return;
        String keyword = searchField.getText().trim();
        List<FileInfo> results = keyword.isEmpty() ? peer.getAllAvailableFiles() : peer.search(keyword);
        System.out.println("[GUI] searchFiles returned " + results.size() + " files from tracker/database");
        updateFileTable(results);
        log("🔍 Tìm thấy " + results.size() + " files" + (keyword.isEmpty() ? "" : " cho '" + keyword + "'"));
    }

    private void refreshFileList() {
        if (peer == null)
            return;
        List<FileInfo> files = peer.getAllAvailableFiles();
        System.out.println("[GUI] refreshFileList returned " + files.size() + " files from tracker/database");
        updateFileTable(files);
        log("🔄 Đã làm mới: " + files.size() + " files");
    }

    private void updateFileTable(List<FileInfo> files) {
        tableModel.setRowCount(0);
        for (FileInfo f : files) {
            String hashDisplay = "N/A";
            if (f.getFileHash() != null && f.getFileHash().length() >= 8) {
                hashDisplay = f.getFileHash().substring(0, 8) + "...";
            }
            tableModel.addRow(new Object[] {
                    f.getFileName(),
                    f.getFormattedSize(),
                    f.getSeedCount() > 0 ? f.getSeedCount() : 1,
                    f.getPeerIP() + ":" + f.getPeerPort(),
                    hashDisplay
            });
        }
    }

    private void downloadSelectedFile() {
        if (peer == null || fileTable.getSelectedRow() < 0)
            return;

        int[] selectedRows = fileTable.getSelectedRows();
        String savePath = null;

        // Nếu bật chế độ hỏi đường dẫn -> Hỏi 1 lần cho tất cả
        if (isAskForDownloadPath) {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Chọn thư mục lưu file");

            // Set default directory to previous selected if possible or downloads
            if (peer.getFileManager().getDownloadFolder() != null) {
                chooser.setCurrentDirectory(new File(peer.getFileManager().getDownloadFolder()));
            }

            if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                savePath = chooser.getSelectedFile().getAbsolutePath();
            } else {
                return; // User hủy
            }
        }

        for (int row : selectedRows) {
            String fileName = (String) tableModel.getValueAt(row, 0);
            List<FileInfo> files = peer.search(fileName);
            if (!files.isEmpty()) {
                String finalSavePath = savePath;

                // Check if file exists before downloading
                File targetFile = peer.getFileManager().getTargetFile(fileName, finalSavePath);
                if (targetFile.exists()) {
                    int k = JOptionPane.showConfirmDialog(
                            this,
                            "File '" + fileName + "' da ton tai.\nBan co muon ghi de khong?",
                            "Xac nhan ghi de",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE);
                    if (k != JOptionPane.YES_OPTION) {
                        continue; // Skip this file
                    }
                }

                new Thread(() -> peer.download(files.get(0), finalSavePath)).start();
            }
        }
    }

    private void downloadMultiSource() {
        if (peer == null || fileTable.getSelectedRow() < 0 || downloader == null)
            return;

        int[] selectedRows = fileTable.getSelectedRows();
        String savePath = null;

        // Nếu bật chế độ hỏi đường dẫn -> Hỏi 1 lần cho tất cả
        if (isAskForDownloadPath) {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Chọn thư mục lưu file (Multi-source)");
            if (peer.getFileManager().getDownloadFolder() != null) {
                chooser.setCurrentDirectory(new File(peer.getFileManager().getDownloadFolder()));
            }

            if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                savePath = chooser.getSelectedFile().getAbsolutePath();
            } else {
                return; // User hủy
            }
        }

        for (int row : selectedRows) {
            String fileName = (String) tableModel.getValueAt(row, 0);
            List<FileInfo> files = peer.search(fileName);
            if (!files.isEmpty()) {
                downloader.downloadFile(files.get(0), savePath);
            }
        }
    }

    /**
     * Hiển thị dialog cài đặt
     */
    private void showSettingsDialog() {
        JDialog dialog = new JDialog(this, "Cài đặt", true);
        dialog.setSize(500, 300); // Increased size
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout());

        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        content.setBackground(BG_DARK);

        // Option 1: Ask for download path
        JCheckBox askPathCb = new JCheckBox("Luôn hỏi nơi lưu file khi tải xuống");
        askPathCb.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        askPathCb.setForeground(TEXT_PRIMARY);
        askPathCb.setBackground(BG_DARK);
        askPathCb.setFocusPainted(false);
        askPathCb.setSelected(isAskForDownloadPath);

        // Option 2: Default Download Folder
        JPanel folderPanel = new JPanel(new BorderLayout(5, 5));
        folderPanel.setBackground(BG_DARK);
        folderPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0)); // Add spacing

        JLabel folderLabel = new JLabel("Thư mục download mặc định:");
        folderLabel.setForeground(TEXT_SECONDARY);

        JPanel inputPanel = new JPanel(new BorderLayout(5, 0));
        inputPanel.setBackground(BG_DARK);

        JTextField folderField = createStyledTextField("Mặc định: downloads", 20);
        folderField.setEditable(false);
        // Hien thi duong dan hien tai (tu config hoac peer)
        File displayPath = new File(currentDownloadFolder);
        folderField.setText(displayPath.getAbsolutePath());

        JButton browseBtn = createSmallButton("Chọn...", BG_INPUT);
        browseBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setCurrentDirectory(new File(currentDownloadFolder));

            if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                folderField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        inputPanel.add(folderField, BorderLayout.CENTER);
        inputPanel.add(browseBtn, BorderLayout.EAST);

        folderPanel.add(folderLabel, BorderLayout.NORTH);
        folderPanel.add(inputPanel, BorderLayout.CENTER);

        // Add to main form panel
        JPanel formPanel = new JPanel();
        formPanel.setLayout(new BoxLayout(formPanel, BoxLayout.Y_AXIS));
        formPanel.setBackground(BG_DARK);

        // Align left
        askPathCb.setAlignmentX(Component.LEFT_ALIGNMENT);
        folderPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        formPanel.add(askPathCb);
        formPanel.add(Box.createVerticalStrut(15));
        formPanel.add(folderPanel);

        content.add(formPanel, BorderLayout.NORTH); // Use NORTH to avoid stretching vertically

        // Buttons
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnPanel.setBackground(BG_DARK);

        JButton saveBtn = createStyledButton("Lưu", PRIMARY);
        saveBtn.addActionListener(e -> {
            isAskForDownloadPath = askPathCb.isSelected();
            String newPath = folderField.getText();

            if (!newPath.isEmpty()) {
                currentDownloadFolder = newPath;
                // Update active peer if running
                if (peer != null && !newPath.equals(peer.getFileManager().getDownloadFolder())) {
                    peer.getFileManager().setDownloadFolder(newPath);
                }
            }

            dialog.dispose();
            log("[Settings] Đã cập nhật cài đặt (Phiên hiện tại)");
        });

        JButton cancelBtn = createStyledButton("Huy", BG_INPUT);
        cancelBtn.addActionListener(e -> dialog.dispose());

        btnPanel.add(cancelBtn);
        btnPanel.add(saveBtn);

        dialog.add(content, BorderLayout.CENTER);
        dialog.add(btnPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);
    }

    private boolean confirm(String message) {
        return JOptionPane.showConfirmDialog(this, message, "Xác nhận",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    private void pauseDownload() {
        if (downloader != null)
            downloader.pause();
    }

    private void resumeDownload() {
        // TODO: Get selected file and resume
        log("▶️ Chức năng resume - chọn file từ danh sách");
    }

    private void cancelDownload() {
        if (downloader != null)
            downloader.cancel();
    }

    private void shareNewFile() {
        if (peer == null)
            return;
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (File file : chooser.getSelectedFiles()) {
                if (peer.shareFile(file)) {
                    log("📤 Đã chia sẻ: " + file.getName());
                }
            }
            refreshSharedFiles();
        }
    }

    private void refreshSharedFiles() {
        if (peer == null)
            return;
        sharedTableModel.setRowCount(0);
        Map<String, File> files = peer.getFileManager().getSharedFiles();
        for (String fileName : files.keySet()) {
            File f = files.get(fileName);
            // Check share status
            boolean isShared = peer.getClient().getShareStatus(fileName);
            sharedTableModel.addRow(new Object[] {
                    fileName,
                    formatSize(f.length()),
                    isShared ? "Đang chia sẻ" : "Đã ẩn"
            });
        }
    }

    private void openSharedFolder() {
        try {
            if (peer != null) {
                Desktop.getDesktop().open(new File(peer.getFileManager().getSharedFolder()));
            }
        } catch (Exception e) {
            log("Không thể mở thư mục shared");
        }
    }

    private void handleDeleteAction() {
        if (peer == null || sharedTable.getSelectedRow() < 0)
            return;

        int row = sharedTable.getSelectedRow();
        String fileName = (String) sharedTableModel.getValueAt(row, 0);

        // Check current status
        boolean isShared = peer.getClient().getShareStatus(fileName);

        Object[] options = { "Chỉ hủy chia sẻ", "Xóa vĩnh viễn", "Hủy" };
        if (!isShared) {
            options = new Object[] { "Chia sẻ lại", "Xóa vĩnh viễn", "Hủy" };
        }

        int choice = JOptionPane.showOptionDialog(this,
                "Bạn muốn làm gì với file '" + fileName + "'?",
                "Quản lý file",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[2]);

        if (choice == 0) { // Stop Sharing or Reshare
            if (isShared) {
                // Stop Sharing
                peer.getClient().updateShareStatus(fileName, false);
                log("Đã hủy chia sẻ: " + fileName);
            } else {
                // Reshare
                peer.getClient().updateShareStatus(fileName, true);
                log("Đã chia sẻ lại: " + fileName);
            }
            refreshSharedFiles();

        } else if (choice == 1) { // Delete Forever
            if (confirm("Chắc chắn xóa vĩnh viễn file " + fileName + "?")) {
                if (peer.getFileManager().removeAndDeleteFile(fileName)) {
                    log("Đã xóa file: " + fileName);
                    refreshSharedFiles();
                } else {
                    showError("Không thể xóa file!");
                }
            }
        }
    }

    /**
     * showSuccess("Đã xóa file: " + fileName);
     * refreshFileList();
     * } else {
     * showError("Không thể xóa file: " + fileName);
     * }
     * }
     * // choice == 2: Hủy - không làm gì
     * }
     * 
     * /**
     * Xóa download đang tải
     */
    private void deleteDownload() {
        int selectedRow = downloadTable.getSelectedRow();
        if (selectedRow < 0) {
            showError("Vui lòng chọn download cần xóa!");
            return;
        }

        String fileName = (String) downloadTableModel.getValueAt(selectedRow, 0);
        String status = (String) downloadTableModel.getValueAt(selectedRow, 4);

        // Xác nhận xóa
        int confirm = JOptionPane.showConfirmDialog(
                this,
                "Bạn có chắc muốn xóa download:\n" + fileName + "\n\nFile tạm (nếu có) sẽ bị xóa.",
                "Xác nhận xóa",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (confirm == JOptionPane.YES_OPTION) {
            // Hủy download nếu đang tải
            if (status.contains("Đang tải")) {
                if (downloader != null) {
                    downloader.cancel();
                }
            }

            // Xóa file tạm
            File tempFile = new File(peer.getFileManager().getDownloadFolder(), fileName + ".tmp");
            if (tempFile.exists()) {
                tempFile.delete();
            }

            // Xóa khỏi bảng
            downloadTableModel.removeRow(selectedRow);
        }
    }

    /**
     * Xóa tất cả downloads đã hoàn thành
     */
    private void clearCompletedDownloads() {
        int removed = 0;
        for (int i = downloadTableModel.getRowCount() - 1; i >= 0; i--) {
            String status = (String) downloadTableModel.getValueAt(i, 4);
            if (status.contains("Hoàn thành") || status.contains("Lỗi")) {
                downloadTableModel.removeRow(i);
                removed++;
            }
        }
        if (removed > 0) {
            log("🧹 Đã xóa " + removed + " downloads hoàn thành/lỗi");
        }
    }

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            String time = java.time.LocalTime.now().toString().substring(0, 8);
            logArea.append("[" + time + "] " + msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Lỗi", JOptionPane.ERROR_MESSAGE);
    }

    private void showSuccess(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Thành công", JOptionPane.INFORMATION_MESSAGE);
    }

    private String formatSize(long size) {
        if (size < 1024)
            return size + " B";
        if (size < 1024 * 1024)
            return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024)
            return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    private String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond < 1024)
            return String.format("%.0f B/s", bytesPerSecond);
        if (bytesPerSecond < 1024 * 1024)
            return String.format("%.1f KB/s", bytesPerSecond / 1024);
        return String.format("%.1f MB/s", bytesPerSecond / (1024 * 1024));
    }

    // ==================== CUSTOM COMPONENTS ====================

    // Rounded Border
    static class RoundedBorder extends AbstractBorder {
        private int radius;
        private Color color;

        RoundedBorder(int radius, Color color) {
            this.radius = radius;
            this.color = color;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(4, 8, 4, 8);
        }
    }

    // Progress Bar Renderer for Table
    static class ProgressBarRenderer extends JProgressBar implements TableCellRenderer {
        ProgressBarRenderer() {
            setMinimum(0);
            setMaximum(100);
            setStringPainted(true);
            setBackground(BG_INPUT);
            setForeground(PRIMARY);
            setBorderPainted(false);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            int progress = (value instanceof Integer) ? (Integer) value : 0;
            setValue(progress);
            setString(progress + "%");

            if (progress >= 100)
                setForeground(SUCCESS);
            else if (progress < 0)
                setForeground(DANGER);
            else
                setForeground(PRIMARY);

            return this;
        }
    }

    // ==================== MAIN ====================

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 6001;

        SwingUtilities.invokeLater(() -> {
            new ModernPeerGUI(port).setVisible(true);
        });
    }
}