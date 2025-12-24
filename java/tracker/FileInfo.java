package tracker;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Class lưu thông tin về một file được chia sẻ trong mạng P2P
 * Đã nâng cấp hỗ trợ Multi-source và Resume download
 */
public class FileInfo implements Serializable {
    private static final long serialVersionUID = 2L;

    private String fileName;      // Tên file
    private long fileSize;        // Kích thước file (bytes)
    private String peerIP;        // IP của peer sở hữu file
    private int peerPort;         // Port của peer
    private String fileHash;      // SHA-256 Hash để verify file

    // Các trường mới cho Multi-source và Resume
    private int fileDbId;         // ID trong database
    private int totalChunks;      // Tổng số chunks
    private List<Integer> availableChunks;  // Danh sách chunks peer này có
    private int seedCount;        // Số peer đang seed file này

    public FileInfo(String fileName, long fileSize, String peerIP, int peerPort) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.peerIP = peerIP;
        this.peerPort = peerPort;
        this.fileHash = calculateSimpleHash(fileName, fileSize);
        this.totalChunks = (int) Math.ceil((double) fileSize / (64 * 1024));
        this.availableChunks = new ArrayList<>();
    }

    private String calculateSimpleHash(String name, long size) {
        return String.format("%08x%08x", name.hashCode(), Long.hashCode(size));
    }

    // Getters và Setters
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public String getPeerIP() { return peerIP; }
    public void setPeerIP(String peerIP) { this.peerIP = peerIP; }

    public int getPeerPort() { return peerPort; }
    public void setPeerPort(int peerPort) { this.peerPort = peerPort; }

    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }

    public int getFileDbId() { return fileDbId; }
    public void setFileDbId(int fileDbId) { this.fileDbId = fileDbId; }

    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }

    public List<Integer> getAvailableChunks() { return availableChunks; }
    public void setAvailableChunks(List<Integer> availableChunks) { this.availableChunks = availableChunks; }

    public int getSeedCount() { return seedCount; }
    public void setSeedCount(int seedCount) { this.seedCount = seedCount; }

    // Format kích thước file cho dễ đọc
    public String getFormattedSize() {
        if (fileSize < 1024) return fileSize + " B";
        else if (fileSize < 1024 * 1024) return String.format("%.2f KB", fileSize / 1024.0);
        else if (fileSize < 1024 * 1024 * 1024) return String.format("%.2f MB", fileSize / (1024.0 * 1024));
        else return String.format("%.2f GB", fileSize / (1024.0 * 1024 * 1024));
    }

    @Override
    public String toString() {
        return String.format("%s (%s) - %s:%d", fileName, getFormattedSize(), peerIP, peerPort);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof FileInfo) {
            FileInfo other = (FileInfo) obj;
            return this.fileName.equals(other.fileName) &&
                    this.peerIP.equals(other.peerIP) &&
                    this.peerPort == other.peerPort;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return (fileName + peerIP + peerPort).hashCode();
    }
}