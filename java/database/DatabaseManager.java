package database;

import tracker.FileInfo;
import java.sql.*;
import java.util.*;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * Quản lý kết nối và thao tác với MySQL Database
 * Yêu cầu: mysql-connector-java và gson trong classpath
 */
public class DatabaseManager {
    private static DatabaseManager instance;
    private Connection connection;
    private final Gson gson = new Gson();

    // Cấu hình database - Laragon default
    private static final String DB_URL = "jdbc:mysql://localhost:3306/p2p_filesharing";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = ""; // Laragon mặc định không có password

    private DatabaseManager() {
        connect();
    }

    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    private void connect() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
            connection.setAutoCommit(true);
            System.out.println("[Database] ✅ Kết nối MySQL thành công!");
            System.out.println("[Database] URL: " + DB_URL);

            checkAndMigrateDatabase(); // Auto-migrate schema
        } catch (ClassNotFoundException e) {
            System.err.println("[Database] ❌ Không tìm thấy MySQL Driver!");
            System.err.println("[Database] Hãy thêm mysql-connector-j-x.x.x.jar vào classpath");
        } catch (SQLException e) {
            System.err.println("[Database] ❌ Lỗi kết nối MySQL: " + e.getMessage());
            System.err.println("[Database] Kiểm tra: 1) Laragon đang chạy? 2) Database 'p2p_filesharing' đã tạo?");
        }
    }

    private void checkAndMigrateDatabase() {
        // Check if is_shared column exists in peer_files
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SHOW COLUMNS FROM peer_files LIKE 'is_shared'");
            if (!rs.next()) {
                System.out.println("[Database] Chưa có cột 'is_shared', đang thêm...");
                stmt.executeUpdate("ALTER TABLE peer_files ADD COLUMN is_shared TINYINT(1) DEFAULT 1");
                System.out.println("[Database] Đã thêm cột 'is_shared' thành công!");
            }
        } catch (SQLException e) {
            System.err.println("[Database] Lỗi migration: " + e.getMessage());
        }
    }

    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed() && connection.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    public Connection getConnection() {
        try {
            if (connection == null || connection.isClosed()) {
                connect();
            }
        } catch (SQLException e) {
            connect();
        }
        return connection;
    }

    // ==================== PEER OPERATIONS ====================

    public int registerPeer(String peerId, String ip, int port) {
        String sql = "INSERT INTO peers (peer_id, ip_address, port, status) VALUES (?, ?, ?, 'online') " +
                "ON DUPLICATE KEY UPDATE status = 'online', last_seen = CURRENT_TIMESTAMP";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, peerId);
            stmt.setString(2, ip);
            stmt.setInt(3, port);
            stmt.executeUpdate();

            ResultSet rs = stmt.getGeneratedKeys();
            if (rs.next())
                return rs.getInt(1);

            // Nếu là UPDATE, lấy ID hiện có
            return getPeerDbId(peerId);
        } catch (SQLException e) {
            e.printStackTrace();
            return -1;
        }
    }

    public void unregisterPeer(String peerId) {
        String sql = "UPDATE peers SET status = 'offline' WHERE peer_id = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, peerId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public int getPeerDbId(String peerId) {
        String sql = "SELECT id FROM peers WHERE peer_id = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, peerId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next())
                return rs.getInt("id");
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return -1;
    }

    public void updatePeerHeartbeat(String peerId) {
        String sql = "UPDATE peers SET last_seen = CURRENT_TIMESTAMP WHERE peer_id = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, peerId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ==================== FILE OPERATIONS ====================

    public int registerFile(String fileName, long fileSize, String fileHash, int totalChunks) {
        // Kiểm tra file đã tồn tại chưa
        String checkSql = "SELECT id FROM files WHERE file_hash = ?";
        try (PreparedStatement checkStmt = getConnection().prepareStatement(checkSql)) {
            checkStmt.setString(1, fileHash);
            ResultSet rs = checkStmt.executeQuery();
            if (rs.next())
                return rs.getInt("id");
        } catch (SQLException e) {
            e.printStackTrace();
        }

        // Tạo mới
        String sql = "INSERT INTO files (file_name, file_size, file_hash, total_chunks) VALUES (?, ?, ?, ?)";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, fileName);
            stmt.setLong(2, fileSize);
            stmt.setString(3, fileHash);
            stmt.setInt(4, totalChunks);
            stmt.executeUpdate();

            ResultSet rs = stmt.getGeneratedKeys();
            if (rs.next())
                return rs.getInt(1);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return -1;
    }

    public void linkPeerToFile(int peerDbId, int fileId, boolean isComplete, List<Integer> availableChunks) {
        String sql = "INSERT INTO peer_files (peer_id, file_id, is_complete, available_chunks, is_shared) VALUES (?, ?, ?, ?, 1) "
                +
                "ON DUPLICATE KEY UPDATE is_complete = ?, available_chunks = ?"; // Removed 'is_shared = 1' to preserve
                                                                                 // hidden state
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            String chunksJson = gson.toJson(availableChunks);
            stmt.setInt(1, peerDbId);
            stmt.setInt(2, fileId);
            stmt.setBoolean(3, isComplete);
            stmt.setString(4, chunksJson);
            stmt.setBoolean(5, isComplete);
            stmt.setString(6, chunksJson);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<FileInfo> searchFiles(String keyword) {
        List<FileInfo> results = new ArrayList<>();
        String sql = "SELECT f.*, p.peer_id, p.ip_address, p.port " +
                "FROM files f " +
                "JOIN peer_files pf ON f.id = pf.file_id " +
                "JOIN peers p ON pf.peer_id = p.id " +
                "WHERE p.status = 'online' AND pf.is_shared = 1 AND f.file_name LIKE ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, "%" + keyword + "%");
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                FileInfo info = new FileInfo(
                        rs.getString("file_name"),
                        rs.getLong("file_size"),
                        rs.getString("ip_address"),
                        rs.getInt("port"));
                info.setFileHash(rs.getString("file_hash"));
                info.setTotalChunks(rs.getInt("total_chunks"));
                info.setFileDbId(rs.getInt("id"));
                results.add(info);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return results;
    }

    public List<FileInfo> getAllFiles() {
        return searchFiles("");
    }

    // Lấy tất cả peer có file (cho multi-source)
    public List<FileInfo> getPeersHavingFile(String fileHash) {
        List<FileInfo> sources = new ArrayList<>();
        String sql = "SELECT f.*, p.peer_id, p.ip_address, p.port, pf.available_chunks " +
                "FROM files f " +
                "JOIN peer_files pf ON f.id = pf.file_id " +
                "JOIN peers p ON pf.peer_id = p.id " +
                "WHERE p.status = 'online' AND pf.is_shared = 1 AND f.file_hash = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, fileHash);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                FileInfo info = new FileInfo(
                        rs.getString("file_name"),
                        rs.getLong("file_size"),
                        rs.getString("ip_address"),
                        rs.getInt("port"));
                info.setFileHash(rs.getString("file_hash"));
                info.setTotalChunks(rs.getInt("total_chunks"));

                // Parse available chunks
                String chunksJson = rs.getString("available_chunks");
                if (chunksJson != null) {
                    List<Integer> chunks = gson.fromJson(chunksJson,
                            new TypeToken<List<Integer>>() {
                            }.getType());
                    info.setAvailableChunks(chunks);
                }
                sources.add(info);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return sources;
    }

    // ==================== DOWNLOAD OPERATIONS (Resume) ====================

    public int createDownload(int fileId, String downloaderPeerId, String fileName,
            long fileSize, int totalChunks) {
        String sql = "INSERT INTO downloads (file_id, downloader_peer_id, file_name, file_size, " +
                "total_chunks, completed_chunks, status) VALUES (?, ?, ?, ?, ?, '[]', 'pending')";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, fileId);
            stmt.setString(2, downloaderPeerId);
            stmt.setString(3, fileName);
            stmt.setLong(4, fileSize);
            stmt.setInt(5, totalChunks);
            stmt.executeUpdate();

            ResultSet rs = stmt.getGeneratedKeys();
            if (rs.next())
                return rs.getInt(1);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return -1;
    }

    public DownloadState getDownloadState(String downloaderPeerId, String fileHash) {
        String sql = "SELECT d.* FROM downloads d " +
                "JOIN files f ON d.file_id = f.id " +
                "WHERE d.downloader_peer_id = ? AND f.file_hash = ? " +
                "AND d.status IN ('pending', 'downloading', 'paused')";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, downloaderPeerId);
            stmt.setString(2, fileHash);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                DownloadState state = new DownloadState();
                state.downloadId = rs.getInt("id");
                state.fileId = rs.getInt("file_id");
                state.fileName = rs.getString("file_name");
                state.fileSize = rs.getLong("file_size");
                state.downloadedSize = rs.getLong("downloaded_size");
                state.totalChunks = rs.getInt("total_chunks");
                state.status = rs.getString("status");

                String chunksJson = rs.getString("completed_chunks");
                state.completedChunks = gson.fromJson(chunksJson,
                        new TypeToken<Set<Integer>>() {
                        }.getType());
                if (state.completedChunks == null)
                    state.completedChunks = new HashSet<>();

                return state;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void updateDownloadProgress(int downloadId, Set<Integer> completedChunks, long downloadedSize) {
        String sql = "UPDATE downloads SET completed_chunks = ?, downloaded_size = ?, " +
                "status = 'downloading' WHERE id = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, gson.toJson(completedChunks));
            stmt.setLong(2, downloadedSize);
            stmt.setInt(3, downloadId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void completeDownload(int downloadId) {
        String sql = "UPDATE downloads SET status = 'completed', completed_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setInt(1, downloadId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void pauseDownload(int downloadId) {
        String sql = "UPDATE downloads SET status = 'paused' WHERE id = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setInt(1, downloadId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ==================== ACTIVITY LOG ====================

    public void logActivity(String peerId, String action, String details) {
        String sql = "INSERT INTO activity_logs (peer_id, action, details) VALUES (?, ?, ?)";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, peerId);
            stmt.setString(2, action);
            stmt.setString(3, details);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ==================== DELETE OPERATIONS ====================

    /**
     * Xóa liên kết peer-file (khi peer hủy chia sẻ file)
     */
    public void unlinkPeerFromFile(String peerId, String fileName) {
        String sql = "DELETE pf FROM peer_files pf " +
                "JOIN peers p ON pf.peer_id = p.id " +
                "JOIN files f ON pf.file_id = f.id " +
                "WHERE p.peer_id = ? AND f.file_name = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, peerId);
            stmt.setString(2, fileName);
            int deleted = stmt.executeUpdate();
            System.out.println("[Database] Xóa liên kết peer-file: " + deleted + " records");
        } catch (SQLException e) {
            e.printStackTrace();
        }

        // Xóa file nếu không còn peer nào chia sẻ
        cleanupOrphanFiles();
    }

    /**
     * Xóa file không còn peer nào chia sẻ
     */
    public void cleanupOrphanFiles() {
        String sql = "DELETE FROM files WHERE id NOT IN (SELECT DISTINCT file_id FROM peer_files)";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            int deleted = stmt.executeUpdate();
            if (deleted > 0) {
                System.out.println("[Database] Xóa " + deleted + " files không còn peer chia sẻ");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Xóa tất cả files của một peer (khi peer disconnect)
     */
    public void removeAllPeerFiles(String peerId) {
        String sql = "DELETE pf FROM peer_files pf " +
                "JOIN peers p ON pf.peer_id = p.id " +
                "WHERE p.peer_id = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, peerId);
            int deleted = stmt.executeUpdate();
            System.out.println("[Database] Xóa " + deleted + " files của peer " + peerId);
        } catch (SQLException e) {
            e.printStackTrace();
        }

        cleanupOrphanFiles();
    }

    /**
     * Xóa download record
     */
    public void deleteDownload(int downloadId) {
        // Xóa sources trước
        String sqlSources = "DELETE FROM download_sources WHERE download_id = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sqlSources)) {
            stmt.setInt(1, downloadId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        // Xóa download
        String sql = "DELETE FROM downloads WHERE id = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setInt(1, downloadId);
            stmt.executeUpdate();
            System.out.println("[Database] Xóa download ID: " + downloadId);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Xóa các downloads đã hoàn thành hoặc bị hủy
     */
    public int cleanupCompletedDownloads(String peerId) {
        String sql = "DELETE FROM downloads WHERE downloader_peer_id = ? AND status IN ('completed', 'failed')";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, peerId);
            int deleted = stmt.executeUpdate();
            System.out.println("[Database] Xóa " + deleted + " downloads hoàn thành/lỗi");
            return deleted;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public void updateShareStatus(String peerId, String fileName, boolean isShared) {
        String sql = "UPDATE peer_files pf " +
                "JOIN peers p ON pf.peer_id = p.id " +
                "JOIN files f ON pf.file_id = f.id " +
                "SET pf.is_shared = ? " +
                "WHERE p.peer_id = ? AND f.file_name = ?";

        System.out.println("[Database] DEBUG SQL EXECUTION:");
        System.out.println("Query: " + sql);
        System.out.println("Param 1 (isShared): " + isShared);
        System.out.println("Param 2 (peerId): " + peerId);
        System.out.println("Param 3 (fileName): " + fileName + " [Length: " + fileName.length() + "]");

        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setBoolean(1, isShared);
            stmt.setString(2, peerId);
            stmt.setString(3, fileName);
            int updated = stmt.executeUpdate();
            System.out.println("[Database] Cập nhật trạng thái file " + fileName + ": "
                    + (isShared ? "SHARED" : "HIDDEN") + " (" + updated + " rows)");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public boolean getShareStatus(String peerId, String fileName) {
        String sql = "SELECT pf.is_shared FROM peer_files pf " +
                "JOIN peers p ON pf.peer_id = p.id " +
                "JOIN files f ON pf.file_id = f.id " +
                "WHERE p.peer_id = ? AND f.file_name = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, peerId);
            stmt.setString(2, fileName);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getBoolean("is_shared");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return true; // Default to shared if checking fails or not found (safest assumption for
                     // visibility, or logic can handle)
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // Inner class for download state
    public static class DownloadState {
        public int downloadId;
        public int fileId;
        public String fileName;
        public long fileSize;
        public long downloadedSize;
        public int totalChunks;
        public Set<Integer> completedChunks;
        public String status;

        public Set<Integer> getMissingChunks() {
            Set<Integer> missing = new HashSet<>();
            for (int i = 0; i < totalChunks; i++) {
                if (!completedChunks.contains(i)) {
                    missing.add(i);
                }
            }
            return missing;
        }
    }
}