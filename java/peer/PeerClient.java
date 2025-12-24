package peer;

import protocol.Message;
import tracker.FileInfo;
import utils.NetworkUtils;
import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Client của Peer - Kết nối Tracker và tải file từ peer khác
 */
public class PeerClient {
    private String trackerHost;
    private int trackerPort;
    private int localPort; // Port của PeerServer local
    private FileManager fileManager;

    // Callback cho UI
    private DownloadCallback callback;

    public interface DownloadCallback {
        void onDownloadStarted(String fileName, String fromPeer);

        // Updated signature: include speed (doule KB/s)
        void onDownloadProgress(String fileName, int percent, long downloaded, long total, double speed);

        void onDownloadCompleted(String fileName);

        void onDownloadFailed(String fileName, String error);
    }

    public PeerClient(String trackerHost, int trackerPort, int localPort, FileManager fileManager) {
        this.trackerHost = trackerHost;
        this.trackerPort = trackerPort;
        this.localPort = localPort;
        this.fileManager = fileManager;

        // Determine correct IP for ID to match what Tracker sees
        String myIp = NetworkUtils.getLocalIPAddress();
        if (trackerHost.equals("localhost") || trackerHost.equals("127.0.0.1")) {
            myIp = "127.0.0.1";
        }
        this.localPeerId = myIp + ":" + localPort;
    }

    private String localPeerId;

    // ... rest of the file

    public void setCallback(DownloadCallback callback) {
        this.callback = callback;
    }

    // ==================== TRACKER OPERATIONS ====================

    /**
     * Đăng ký với Tracker
     */
    public boolean registerWithTracker() {
        try {
            Message msg = new Message(Message.Type.REGISTER);
            msg.setPeerPort(localPort);

            Message response = sendToTracker(msg);
            if (response != null && response.getType() == Message.Type.REGISTER_OK) {
                System.out.println("[PeerClient] " + response.getContent());
                return true;
            }
        } catch (Exception e) {
            System.err.println("[PeerClient] Lỗi đăng ký: " + e.getMessage());
        }
        return false;
    }

    /**
     * Hủy đăng ký với Tracker
     */
    public boolean unregisterFromTracker() {
        try {
            Message msg = new Message(Message.Type.UNREGISTER);
            msg.setPeerPort(localPort);

            Message response = sendToTracker(msg);
            return response != null && response.getType() == Message.Type.REGISTER_OK;
        } catch (Exception e) {
            System.err.println("[PeerClient] Lỗi hủy đăng ký: " + e.getMessage());
        }
        return false;
    }

    /**
     * Publish file lên Tracker
     */
    public boolean publishFile(FileInfo fileInfo) {
        try {
            Message msg = new Message(Message.Type.PUBLISH);
            msg.setFileInfo(fileInfo);
            msg.setPeerPort(localPort);

            Message response = sendToTracker(msg);
            if (response != null && response.getType() == Message.Type.REGISTER_OK) {
                System.out.println("[PeerClient] " + response.getContent());
                return true;
            }
        } catch (Exception e) {
            System.err.println("[PeerClient] Lỗi publish: " + e.getMessage());
        }
        return false;
    }

    /**
     * Unpublish file (xóa khỏi Tracker)
     */
    public boolean unpublishFile(String fileName) {
        try {
            Message msg = new Message(Message.Type.UNPUBLISH, fileName);
            msg.setPeerPort(localPort);

            Message response = sendToTracker(msg);
            if (response != null && response.getType() == Message.Type.REGISTER_OK) {
                System.out.println("[PeerClient] " + response.getContent());
                return true;
            }
        } catch (Exception e) {
            System.err.println("[PeerClient] Lỗi unpublish: " + e.getMessage());
        }
        return false;
    }

    /**
     * Publish tất cả files trong thư mục shared
     */
    public int publishAllFiles() {
        int count = 0;
        try {
            String localIP = InetAddress.getLocalHost().getHostAddress();
            List<FileInfo> files = fileManager.getSharedFileInfos(localIP, localPort);

            for (FileInfo file : files) {
                if (publishFile(file)) {
                    count++;
                }
            }
            System.out.println("[PeerClient] Đã publish " + count + " files");
        } catch (Exception e) {
            System.err.println("[PeerClient] Lỗi publish all: " + e.getMessage());
        }
        return count;
    }

    public void updateShareStatus(String fileName, boolean isShared) {
        // This needs access to DatabaseManager. PeerClient doesn't hold it directly?
        // Peer has PeerClient and PeerServer. PeerServer uses FileManager.
        // MultiSourceDownloader uses DatabaseManager.

        // Actually, DatabaseManager is a singleton. We can call it directly or via a
        // specific manager.
        // Let's call it directly here for convenience as it is a client-side operation
        // impacting query results.
        database.DatabaseManager.getInstance().updateShareStatus(localPeerId, fileName, isShared);
    }

    public boolean getShareStatus(String fileName) {
        return database.DatabaseManager.getInstance().getShareStatus(localPeerId, fileName);
    }

    /**
     * Tìm kiếm file trên Tracker
     */
    public List<FileInfo> searchFiles(String keyword) {
        try {
            Message msg = new Message(Message.Type.SEARCH, keyword);
            msg.setPeerPort(localPort);

            Message response = sendToTracker(msg);
            if (response != null && response.getType() == Message.Type.SEARCH_RESULT) {
                return response.getFileList();
            }
        } catch (Exception e) {
            System.err.println("[PeerClient] Lỗi tìm kiếm: " + e.getMessage());
        }
        return new ArrayList<>();
    }

    /**
     * Lấy danh sách tất cả files
     */
    public List<FileInfo> getAllFiles() {
        try {
            Message msg = new Message(Message.Type.GET_ALL_FILES);
            msg.setPeerPort(localPort);

            Message response = sendToTracker(msg);
            if (response != null && response.getType() == Message.Type.FILE_LIST) {
                return response.getFileList();
            }
        } catch (Exception e) {
            System.err.println("[PeerClient] Lỗi lấy danh sách files: " + e.getMessage());
        }
        return new ArrayList<>();
    }

    /**
     * Gửi message đến Tracker và nhận response
     */
    private Message sendToTracker(Message msg) {
        try (Socket socket = new Socket(trackerHost, trackerPort);
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            out.writeObject(msg);
            out.flush();

            return (Message) in.readObject();
        } catch (Exception e) {
            System.err.println("[PeerClient] Lỗi kết nối Tracker: " + e.getMessage());
            return null;
        }
    }

    // ==================== PEER-TO-PEER OPERATIONS ====================

    public boolean downloadFile(FileInfo fileInfo) {
        return downloadFile(fileInfo, null);
    }

    /**
     * Tải file từ một Peer khác
     */
    public boolean downloadFile(FileInfo fileInfo, String savePath) {
        String fileName = fileInfo.getFileName();
        String peerIP = fileInfo.getPeerIP();
        int peerPort = fileInfo.getPeerPort();

        System.out.println("[PeerClient] Bắt đầu tải " + fileName + " từ " + peerIP + ":" + peerPort);

        if (callback != null) {
            callback.onDownloadStarted(fileName, peerIP + ":" + peerPort);
        }

        Socket socket = null;
        ObjectOutputStream out = null;
        ObjectInputStream in = null;

        try {
            socket = new Socket(peerIP, peerPort);
            socket.setSoTimeout(60000); // Timeout 60 giây

            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());

            // Gửi yêu cầu tải file
            Message request = new Message(Message.Type.REQUEST_FILE, fileName);
            out.writeObject(request);
            out.flush();

            // Nhận file theo chunks
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            long totalReceived = 0;
            long fileSize = 0;

            // Speed calculation variables
            long lastTime = System.currentTimeMillis();
            long lastBytes = 0;
            double currentSpeed = 0;
            int lastLogPercent = -1; // Avoid duplicate logging

            while (true) {
                Message response = (Message) in.readObject();

                if (response.getType() == Message.Type.FILE_NOT_FOUND) {
                    System.err.println("[PeerClient] " + response.getContent());
                    if (callback != null) {
                        callback.onDownloadFailed(fileName, "File không tồn tại trên peer");
                    }
                    return false;
                }

                if (response.getType() == Message.Type.TRANSFER_COMPLETE) {
                    break;
                }

                if (response.getType() == Message.Type.FILE_DATA) {
                    byte[] chunk = response.getData();
                    fileSize = response.getFileSize();
                    baos.write(chunk);
                    totalReceived += chunk.length;

                    // Calculate speed
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastTime >= 1000) {
                        long diff = totalReceived - lastBytes;
                        currentSpeed = (double) diff / 1024.0; // KB/s
                        lastTime = currentTime;
                        lastBytes = totalReceived;
                    }

                    int percent = (int) ((totalReceived * 100) / fileSize);
                    if (callback != null) {
                        callback.onDownloadProgress(fileName, percent, totalReceived, fileSize, currentSpeed);
                    }

                    // Log mỗi 25%
                    if (percent % 25 == 0 && percent != lastLogPercent) {
                        System.out.println("[PeerClient] Đang tải " + fileName + ": " + percent + "% ("
                                + String.format("%.2f", currentSpeed) + " KB/s)");
                        lastLogPercent = percent;
                    }
                }
            }

            System.out.println(
                    "[PeerClient] Download finished. Total received: " + totalReceived + " / Expected: " + fileSize);
            if (totalReceived != fileSize) {
                System.err.println("[PeerClient] WARNING: File size mismatch! Hash will likely fail.");
            }

            // Lưu file
            fileManager.saveFile(fileName, baos.toByteArray(), savePath);

            System.out.println("[PeerClient] Hoàn thành tải file: " + fileName);
            if (callback != null) {
                callback.onDownloadCompleted(fileName);
            }

            // ⭐ TỰ ĐỘNG SEEDING: Publish file vừa tải lên Tracker
            // ⭐ TỰ ĐỘNG SEEDING: Publish file vừa tải lên Tracker
            try {
                // Chỉ publish nếu file nằm trong folder quản lý (savePath null hoặc nằm trong
                // shared/download)
                File downloadedFile = fileManager.getTargetFile(fileName, savePath);
                System.out.println("[PeerClient] Auto-seeding check for: " + downloadedFile.getAbsolutePath());

                if (downloadedFile.exists()) {
                    FileInfo newFileInfo = new FileInfo(downloadedFile.getName(), downloadedFile.length(),
                            NetworkUtils.getLocalIPAddress(), localPort);

                    String hash = fileManager.calculateFileHash(fileName);
                    System.out.println("[PeerClient] Calculated Hash: " + hash);

                    // Cần hash đúng để tracker nhập
                    newFileInfo.setFileHash(hash);

                    if (newFileInfo.getFileHash() != null) {
                        boolean success = publishFile(newFileInfo);
                        System.out.println("[PeerClient] Auto-seeding call result: " + success);
                    } else {
                        System.err.println(
                                "[PeerClient] Auto-seeding failed: Hash is null (File NOT in sharedFiles map?)");
                        System.out.println("[PeerClient] Shared Keys: " + fileManager.getSharedFiles().keySet());
                    }
                } else {
                    System.err.println("[PeerClient] File not found on disk: " + downloadedFile.getAbsolutePath());
                }
            } catch (Exception e) {
                System.err.println("[PeerClient] Lỗi auto-seeding: " + e.getMessage());
                e.printStackTrace();
            }

            return true;

        } catch (

        SocketTimeoutException e) {
            System.err.println("[PeerClient] Timeout khi tải file: " + fileName);
            if (callback != null) {
                callback.onDownloadFailed(fileName, "Timeout - Peer không phản hồi");
            }
            return false;
        } catch (Exception e) {
            System.err.println("[PeerClient] Lỗi tải file: " + e.getMessage());
            if (callback != null) {
                callback.onDownloadFailed(fileName, e.getMessage());
            }
            return false;
        } finally {
            try {
                if (in != null)
                    in.close();
                if (out != null)
                    out.close();
                if (socket != null)
                    socket.close();
            } catch (IOException e) {
            }
        }
    }

    /**
     * Tải file từ nhiều nguồn (nếu có)
     */
    public boolean downloadFileFromMultipleSources(String fileName, List<FileInfo> sources) {
        // Sắp xếp sources theo một tiêu chí (có thể thêm logic chọn peer tốt nhất)
        for (FileInfo source : sources) {
            if (downloadFile(source)) {
                return true;
            }
            System.out.println("[PeerClient] Thử nguồn tiếp theo...");
        }
        return false;
    }

    // Getters
    public String getTrackerHost() {
        return trackerHost;
    }

    public int getTrackerPort() {
        return trackerPort;
    }

    public int getLocalPort() {
        return localPort;
    }
}