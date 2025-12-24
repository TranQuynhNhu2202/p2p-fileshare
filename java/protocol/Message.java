package protocol;

import java.io.Serializable;
import java.util.List;
import tracker.FileInfo;

/**
 * Class định nghĩa các message được trao đổi trong hệ thống P2P
 */
public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    // Các loại message
    public enum Type {
        // Peer -> Tracker
        REGISTER,           // Đăng ký peer với tracker
        UNREGISTER,         // Hủy đăng ký
        PUBLISH,            // Công bố file mới
        UNPUBLISH,          // Gỡ file khỏi danh sách
        SEARCH,             // Tìm kiếm file
        GET_ALL_FILES,      // Lấy danh sách tất cả files

        // Tracker -> Peer
        REGISTER_OK,        // Đăng ký thành công
        SEARCH_RESULT,      // Kết quả tìm kiếm
        FILE_LIST,          // Danh sách files
        ERROR,              // Lỗi

        // Peer -> Peer
        REQUEST_FILE,       // Yêu cầu tải file (toàn bộ)
        REQUEST_CHUNK,      // Yêu cầu tải 1 chunk (cho multi-source)
        FILE_DATA,          // Dữ liệu file
        CHUNK_DATA,         // Dữ liệu 1 chunk
        FILE_NOT_FOUND,     // File không tồn tại
        TRANSFER_COMPLETE,  // Hoàn thành truyền file

        // Peer -> Tracker (cho resume/multi-source)
        GET_FILE_SOURCES,   // Lấy danh sách peer có file
        FILE_SOURCES        // Danh sách peer sources
    }

    // Thêm các trường mới cho chunk-based download
    private int chunkIndex;     // Index của chunk (cho REQUEST_CHUNK)
    private int chunkSize;      // Kích thước chunk

    private Type type;
    private String content;           // Nội dung text (tên file, thông báo...)
    private List<FileInfo> fileList;  // Danh sách file (cho SEARCH_RESULT, FILE_LIST)
    private FileInfo fileInfo;        // Thông tin 1 file
    private byte[] data;              // Dữ liệu file
    private int peerPort;             // Port của peer gửi message
    private long fileSize;            // Kích thước file
    private long offset;              // Vị trí bắt đầu đọc (cho download từng phần)

    public Message(Type type) {
        this.type = type;
    }

    public Message(Type type, String content) {
        this.type = type;
        this.content = content;
    }

    // Getters và Setters
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<FileInfo> getFileList() { return fileList; }
    public void setFileList(List<FileInfo> fileList) { this.fileList = fileList; }

    public FileInfo getFileInfo() { return fileInfo; }
    public void setFileInfo(FileInfo fileInfo) { this.fileInfo = fileInfo; }

    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }

    public int getPeerPort() { return peerPort; }
    public void setPeerPort(int peerPort) { this.peerPort = peerPort; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public long getOffset() { return offset; }
    public void setOffset(long offset) { this.offset = offset; }

    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }

    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }

    @Override
    public String toString() {
        return "Message{type=" + type + ", content='" + content + "'}";
    }
}