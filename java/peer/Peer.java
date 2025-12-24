package peer;

import tracker.FileInfo;
import utils.NetworkUtils;
import java.io.File;
import java.util.List;

/**
 * Class chính đại diện cho một Peer trong mạng P2P
 * Tích hợp cả PeerServer (phục vụ upload) và PeerClient (download/tracker)
 */
public class Peer {
    private String peerID;
    private String localIP;
    private int port;

    private PeerServer server;
    private PeerClient client;
    private FileManager fileManager;

    private Thread serverThread;
    private boolean isRunning;

    // Thư mục mặc định
    private static final String DEFAULT_SHARED_FOLDER = "shared_files";
    private static final String DEFAULT_DOWNLOAD_FOLDER = "downloads";

    public Peer(int port, String trackerHost, int trackerPort) {
        this(port, trackerHost, trackerPort, DEFAULT_SHARED_FOLDER, DEFAULT_DOWNLOAD_FOLDER);
    }

    public Peer(int port, String trackerHost, int trackerPort,
            String sharedFolder, String downloadFolder) {
        this.port = port;

        // ⭐ SỬ DỤNG NetworkUtils để lấy IP LAN thực
        this.localIP = NetworkUtils.getLocalIPAddress();

        this.peerID = localIP + ":" + port;

        // Khởi tạo các thành phần
        this.fileManager = new FileManager(sharedFolder, downloadFolder);
        this.server = new PeerServer(port, peerID, fileManager);
        this.client = new PeerClient(trackerHost, trackerPort, port, fileManager);

        System.out.println("═══════════════════════════════════════════");
        System.out.println("         PEER được tạo");
        System.out.println("         ID: " + peerID);
        System.out.println("         IP LAN: " + localIP);
        System.out.println("         Port: " + port);
        System.out.println("         Tracker: " + trackerHost + ":" + trackerPort);
        System.out.println("═══════════════════════════════════════════");
    }

    public void setCallback(PeerClient.DownloadCallback callback) {
        client.setCallback(callback);
    }

    /**
     * Khởi động Peer (server + đăng ký tracker)
     */
    public boolean start() {
        // Khởi động PeerServer
        serverThread = new Thread(server);
        serverThread.start();
        isRunning = true;

        // Đăng ký với Tracker
        if (client.registerWithTracker()) {
            System.out.println("[Peer] Đã kết nối với Tracker");

            // Publish tất cả files trong thư mục shared
            int publishedCount = client.publishAllFiles();
            System.out.println("[Peer] Đã publish " + publishedCount + " files");

            return true;
        } else {
            System.err.println("[Peer] Không thể kết nối Tracker!");
            return false;
        }
    }

    /**
     * Dừng Peer
     */
    public void stop() {
        System.out.println("[Peer] Đang tắt...");

        // Hủy đăng ký với Tracker
        client.unregisterFromTracker();

        // Dừng server
        server.stop();
        isRunning = false;

        if (serverThread != null) {
            serverThread.interrupt();
        }

        System.out.println("[Peer] Đã tắt");
    }

    // ==================== FILE OPERATIONS ====================

    /**
     * Tìm kiếm file
     */
    public List<FileInfo> search(String keyword) {
        return client.searchFiles(keyword);
    }

    /**
     * Lấy danh sách tất cả files trong mạng
     */
    public List<FileInfo> getAllAvailableFiles() {
        return client.getAllFiles();
    }

    /**
     * Tải file
     */
    public boolean download(FileInfo fileInfo) {
        return download(fileInfo, null);
    }

    /**
     * Tải file với đường dẫn tùy chọn
     */
    public boolean download(FileInfo fileInfo, String savePath) {
        // Delegate to client directly, let GUI handle existence checks/confirmations
        return client.downloadFile(fileInfo, savePath);
    }

    /**
     * Chia sẻ file mới
     */
    public boolean shareFile(File file) {
        try {
            FileInfo fileInfo = fileManager.addFileToShare(file, localIP, port);
            return client.publishFile(fileInfo);
        } catch (Exception e) {
            System.err.println("[Peer] Lỗi chia sẻ file: " + e.getMessage());
            return false;
        }
    }

    /**
     * Hủy chia sẻ file (xóa khỏi mạng và thư mục local)
     */
    public boolean unshareFile(String fileName) {
        try {
            // 1. Gửi yêu cầu unpublish lên Tracker
            boolean unpublished = client.unpublishFile(fileName);

            // 2. Xóa khỏi FileManager
            fileManager.removeFromShare(fileName);

            // 3. Xóa file vật lý (tùy chọn)
            File file = new File(fileManager.getSharedFolder(), fileName);
            if (file.exists()) {
                boolean deleted = file.delete();
                System.out.println("[Peer] Xóa file vật lý: " + fileName + " - " + (deleted ? "OK" : "FAILED"));
            }

            System.out.println("[Peer] Đã hủy chia sẻ: " + fileName);
            return unpublished;
        } catch (Exception e) {
            System.err.println("[Peer] Lỗi hủy chia sẻ file: " + e.getMessage());
            return false;
        }
    }

    /**
     * Refresh danh sách file và publish lại
     */
    public void refreshSharedFiles() {
        fileManager.scanSharedFolder();
        client.publishAllFiles();
    }

    // ==================== CALLBACKS ====================

    public void setDownloadCallback(PeerClient.DownloadCallback callback) {
        client.setCallback(callback);
    }

    public void setUploadCallback(PeerServer.TransferCallback callback) {
        server.setCallback(callback);
    }

    // ==================== GETTERS ====================

    public String getPeerID() {
        return peerID;
    }

    public String getLocalIP() {
        return localIP;
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public FileManager getFileManager() {
        return fileManager;
    }

    public PeerClient getClient() {
        return client;
    }

    public PeerServer getServer() {
        return server;
    }

    public int getSharedFileCount() {
        return fileManager.getSharedFileCount();
    }
}