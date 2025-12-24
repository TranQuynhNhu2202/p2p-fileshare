package peer;

import protocol.Message;
import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.*;

/**
 * Server của mỗi Peer - Phục vụ yêu cầu tải file từ các peer khác
 */
public class PeerServer implements Runnable {
    private int port;
    private ServerSocket serverSocket;
    private FileManager fileManager;
    private boolean running;
    private ExecutorService executor;

    // Callback để thông báo UI
    private TransferCallback callback;

    public interface TransferCallback {
        void onUploadStarted(String fileName, String toPeer);

        void onUploadProgress(String fileName, int percent);

        void onUploadCompleted(String fileName);

        void onUploadFailed(String fileName, String error);
    }

    private String peerID; // ID của peer hiện tại (để check quyền)

    public PeerServer(int port, String peerID, FileManager fileManager) {
        this.port = port;
        this.peerID = peerID;
        this.fileManager = fileManager;
        this.executor = Executors.newCachedThreadPool();
    }

    public void setCallback(TransferCallback callback) {
        this.callback = callback;
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            System.out.println("[PeerServer] Đang lắng nghe trên port " + port);

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    executor.execute(new FileUploadHandler(clientSocket));
                } catch (SocketException e) {
                    if (running) {
                        System.err.println("[PeerServer] Socket error: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[PeerServer] Không thể khởi động server: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            executor.shutdownNow();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Handler xử lý yêu cầu tải file
     */
    private class FileUploadHandler implements Runnable {
        private Socket socket;

        public FileUploadHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            ObjectOutputStream out = null;
            ObjectInputStream in = null;
            String clientInfo = socket.getInetAddress().getHostAddress();

            try {
                out = new ObjectOutputStream(socket.getOutputStream());
                in = new ObjectInputStream(socket.getInputStream());

                Message request = (Message) in.readObject();
                System.out.println("[PeerServer] Nhận yêu cầu từ " + clientInfo + ": " + request.getType());

                if (request.getType() == Message.Type.REQUEST_FILE) {
                    handleFileRequest(request, out, clientInfo);
                } else if (request.getType() == Message.Type.REQUEST_CHUNK) {
                    handleChunkRequest(request, out, clientInfo);
                }

            } catch (Exception e) {
                System.err.println("[PeerServer] Lỗi xử lý yêu cầu: " + e.getMessage());
                if (callback != null) {
                    callback.onUploadFailed("unknown", e.getMessage());
                }
            } finally {
                closeConnection(in, out, socket);
            }
        }

        private void handleFileRequest(Message request, ObjectOutputStream out, String clientInfo)
                throws IOException {
            String fileName = request.getContent();

            if (!fileManager.hasFile(fileName)) {
                // File không tồn tại
                Message response = new Message(Message.Type.FILE_NOT_FOUND);
                response.setContent("File không tồn tại: " + fileName);
                out.writeObject(response);
                System.out.println("[PeerServer] File không tìm thấy: " + fileName);

                // ⭐ LAZY CLEANUP: Nếu file không tồn tại thực tế nhưng DB vẫn còn -> Xóa khỏi
                // DB peer_files
                database.DatabaseManager.getInstance().unlinkPeerFromFile(peerID, fileName);
                System.out.println("[PeerServer] Lazy Cleanup: Đã gỡ file khỏi DB: " + fileName);
                return;
            }

            // ⭐ KIỂM TRA QUYỀN TRUY CẬP (Is Shared?)
            boolean isShared = database.DatabaseManager.getInstance().getShareStatus(peerID, fileName);
            if (!isShared) {
                System.out.println("[PeerServer] TỪ CHỐI tải file " + fileName + " (đang ẩn)");
                Message response = new Message(Message.Type.FILE_NOT_FOUND);
                // Dùng FILE_NOT_FOUND hoặc ERROR tùy logic, ở đây giả vờ không thấy để bảo mật
                response.setContent("File không tồn tại hoặc đã bị ẩn: " + fileName);
                out.writeObject(response);
                return;
            }

            // Thông báo bắt đầu upload

            // Thông báo bắt đầu upload
            if (callback != null) {
                callback.onUploadStarted(fileName, clientInfo);
            }

            File file = fileManager.getFile(fileName);
            long fileSize = file.length();

            System.out.println("[PeerServer] Bắt đầu gửi file: " + fileName + " (" + fileSize + " bytes)");

            // Gửi file theo chunks để hỗ trợ file lớn
            int chunkSize = 64 * 1024; // 64KB per chunk
            long offset = 0;
            int chunkNumber = 0;
            int totalChunks = (int) Math.ceil((double) fileSize / chunkSize);

            try (FileInputStream fis = new FileInputStream(file)) {
                while (offset < fileSize) {
                    int currentChunkSize = (int) Math.min(chunkSize, fileSize - offset);
                    byte[] chunk = new byte[currentChunkSize];
                    // Ensure we read the full chunk (read(byte[]) acts like read(byte[], 0, len)
                    // but doesn't guarantee full read)
                    int totalBytesRead = 0;
                    while (totalBytesRead < currentChunkSize) {
                        int count = fis.read(chunk, totalBytesRead, currentChunkSize - totalBytesRead);
                        if (count == -1)
                            break; // EOF check
                        totalBytesRead += count;
                    }

                    if (totalBytesRead < currentChunkSize) {
                        // Should rare happen if file size didn't change, but if it does, resize
                        chunk = Arrays.copyOf(chunk, totalBytesRead);
                    }

                    Message dataMsg = new Message(Message.Type.FILE_DATA);
                    dataMsg.setContent(fileName);
                    dataMsg.setData(chunk);
                    dataMsg.setOffset(offset);
                    dataMsg.setFileSize(fileSize);

                    out.writeObject(dataMsg);
                    out.flush();
                    out.reset(); // Quan trọng: reset cache để tránh memory leak

                    offset += currentChunkSize;
                    chunkNumber++;

                    // Cập nhật progress
                    int percent = (int) ((offset * 100) / fileSize);
                    if (callback != null) {
                        callback.onUploadProgress(fileName, percent);
                    }

                    // Log mỗi 10%
                    if (chunkNumber % (totalChunks / 10 + 1) == 0) {
                        System.out.println("[PeerServer] Đang gửi " + fileName + ": " + percent + "%");
                    }
                }
            }

            // Gửi message hoàn thành
            Message completeMsg = new Message(Message.Type.TRANSFER_COMPLETE);
            completeMsg.setContent(fileName);
            out.writeObject(completeMsg);

            System.out.println("[PeerServer] Hoàn thành gửi file: " + fileName);
            if (callback != null) {
                callback.onUploadCompleted(fileName);
            }
        }

        /**
         * Xử lý yêu cầu tải một chunk (cho Multi-source download)
         */
        private void handleChunkRequest(Message request, ObjectOutputStream out, String clientInfo)
                throws IOException {
            String fileName = request.getContent();
            int chunkIndex = request.getChunkIndex();
            long offset = request.getOffset();
            int chunkSize = request.getChunkSize();

            if (!fileManager.hasFile(fileName)) {
                Message response = new Message(Message.Type.FILE_NOT_FOUND);
                response.setContent("File không tồn tại: " + fileName);
                out.writeObject(response);

                // ⭐ LAZY CLEANUP
                database.DatabaseManager.getInstance().unlinkPeerFromFile(peerID, fileName);
                System.out.println("[PeerServer] Lazy Cleanup (Chunk): Đã gỡ file khỏi DB: " + fileName);
                return;
            }

            // ⭐ KIỂM TRA QUYỀN TRUY CẬP (Is Shared?)
            boolean isShared = database.DatabaseManager.getInstance().getShareStatus(peerID, fileName);
            if (!isShared) {
                System.out.println("[PeerServer] TỪ CHỐI gửi chunk " + fileName + " (đang ẩn)");
                Message response = new Message(Message.Type.FILE_NOT_FOUND);
                response.setContent("File đã bị ẩn: " + fileName);
                out.writeObject(response);
                return;
            }

            try {
                byte[] chunkData = fileManager.readFileChunk(fileName, offset, chunkSize);

                if (chunkData != null) {
                    Message response = new Message(Message.Type.CHUNK_DATA);
                    response.setContent(fileName);
                    response.setChunkIndex(chunkIndex);
                    response.setData(chunkData);
                    response.setOffset(offset);
                    out.writeObject(response);
                    out.flush();

                    System.out.println(
                            "[PeerServer] Gửi chunk " + chunkIndex + " của " + fileName + " đến " + clientInfo);
                } else {
                    Message response = new Message(Message.Type.FILE_NOT_FOUND);
                    response.setContent("Không thể đọc chunk: " + chunkIndex);
                    out.writeObject(response);
                }
            } catch (Exception e) {
                Message response = new Message(Message.Type.FILE_NOT_FOUND);
                response.setContent("Lỗi đọc chunk: " + e.getMessage());
                out.writeObject(response);
            }
        }

        private void closeConnection(ObjectInputStream in, ObjectOutputStream out, Socket socket) {
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
}