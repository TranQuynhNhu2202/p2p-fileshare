package tracker;

import database.DatabaseManager;
import protocol.Message;
import utils.NetworkUtils;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Tracker Server v2 - Tích hợp MySQL Database
 * Lưu trữ thông tin Peer và File vào database
 */
public class TrackerServer {
    private int port;
    private ServerSocket serverSocket;
    private boolean running;

    // Vẫn giữ cache trong RAM để truy vấn nhanh
    private Map<String, Set<FileInfo>> peerFiles;
    private Map<String, List<FileInfo>> fileIndex;
    private ExecutorService executor;

    // Database Manager
    private DatabaseManager db;

    public TrackerServer(int port) {
        this.port = port;
        this.peerFiles = new ConcurrentHashMap<>();
        this.fileIndex = new ConcurrentHashMap<>();
        this.executor = Executors.newCachedThreadPool();

        // Khởi tạo Database connection
        this.db = DatabaseManager.getInstance();
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(port);
            running = true;

            // ⭐ Hiển thị IP LAN để các máy khác kết nối
            String lanIP = NetworkUtils.getLocalIPAddress();

            System.out.println("═══════════════════════════════════════════════════════");
            System.out.println("        TRACKER SERVER v2 (MySQL)");
            System.out.println("═══════════════════════════════════════════════════════");
            System.out.println("  Port: " + port);
            System.out.println("  IP LAN: " + lanIP);
            System.out.println("");
            System.out.println("  📡 Các máy khác trong mạng LAN kết nối đến:");
            System.out.println("     " + lanIP + ":" + port);
            System.out.println("");
            System.out.println("  📡 Máy này (localhost) kết nối đến:");
            System.out.println("     localhost:" + port);
            System.out.println("═══════════════════════════════════════════════════════");

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    executor.execute(new ClientHandler(clientSocket));
                } catch (SocketException e) {
                    if (running)
                        e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null)
                serverSocket.close();
            executor.shutdown();
            db.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Handler xử lý từng kết nối từ peer
     */
    private class ClientHandler implements Runnable {
        private Socket socket;
        private ObjectInputStream in;
        private ObjectOutputStream out;
        private String peerID;
        private String peerIP;
        private int peerPort;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                out = new ObjectOutputStream(socket.getOutputStream());
                in = new ObjectInputStream(socket.getInputStream());

                Message msg = (Message) in.readObject();
                peerIP = socket.getInetAddress().getHostAddress();
                peerPort = msg.getPeerPort();
                peerID = peerIP + ":" + peerPort;

                System.out.println("\n[TRACKER] Nhận " + msg.getType() + " từ " + peerID);

                switch (msg.getType()) {
                    case REGISTER:
                        handleRegister();
                        break;
                    case UNREGISTER:
                        handleUnregister();
                        break;
                    case PUBLISH:
                        handlePublish(msg.getFileInfo());
                        break;
                    case UNPUBLISH:
                        handleUnpublish(msg.getContent());
                        break;
                    case SEARCH:
                        handleSearch(msg.getContent());
                        break;
                    case GET_ALL_FILES:
                        handleGetAllFiles();
                        break;
                    case GET_FILE_SOURCES:
                        handleGetFileSources(msg.getContent());
                        break;
                    default:
                        sendError("Unknown message type");
                }
            } catch (Exception e) {
                System.err.println("[TRACKER] Lỗi xử lý client: " + e.getMessage());
            } finally {
                closeConnection();
            }
        }

        private void handleRegister() throws IOException {
            // Lưu vào RAM cache
            peerFiles.putIfAbsent(peerID, ConcurrentHashMap.newKeySet());

            // ⭐ LƯU VÀO DATABASE
            int peerDbId = db.registerPeer(peerID, peerIP, peerPort);

            // Log activity
            db.logActivity(peerID, "CONNECT", "Peer connected from " + peerIP);

            Message response = new Message(Message.Type.REGISTER_OK);
            response.setContent("Đăng ký thành công! PeerID: " + peerID);
            out.writeObject(response);

            System.out.println("[TRACKER] ✅ Peer đăng ký: " + peerID + " (DB ID: " + peerDbId + ")");
            printStatus();
        }

        private void handleUnregister() throws IOException {
            // Xóa khỏi RAM cache
            Set<FileInfo> files = peerFiles.remove(peerID);
            if (files != null) {
                for (FileInfo f : files) {
                    List<FileInfo> list = fileIndex.get(f.getFileName());
                    if (list != null) {
                        list.removeIf(fi -> fi.getPeerIP().equals(f.getPeerIP())
                                && fi.getPeerPort() == f.getPeerPort());
                        if (list.isEmpty())
                            fileIndex.remove(f.getFileName());
                    }
                }
            }

            // ⭐ CẬP NHẬT DATABASE - Xóa tất cả files của peer
            db.removeAllPeerFiles(peerID);
            db.unregisterPeer(peerID);
            db.logActivity(peerID, "DISCONNECT", "Peer disconnected");

            Message response = new Message(Message.Type.REGISTER_OK);
            response.setContent("Hủy đăng ký thành công!");
            out.writeObject(response);

            System.out.println("[TRACKER] 🔴 Peer hủy đăng ký: " + peerID);
            printStatus();
        }

        private void handlePublish(FileInfo fileInfo) throws IOException {
            // Lưu vào RAM cache
            peerFiles.computeIfAbsent(peerID, k -> ConcurrentHashMap.newKeySet()).add(fileInfo);
            fileIndex.computeIfAbsent(fileInfo.getFileName(), k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(fileInfo);

            // ⭐ LƯU VÀO DATABASE
            int peerDbId = db.getPeerDbId(peerID);
            if (peerDbId == -1) {
                // Peer chưa đăng ký, đăng ký trước
                peerDbId = db.registerPeer(peerID, peerIP, peerPort);
            }

            // Tính hash và chunks
            String fileHash = fileInfo.getFileHash();
            int totalChunks = (int) Math.ceil((double) fileInfo.getFileSize() / (64 * 1024));

            // Lưu file vào DB
            int fileDbId = db.registerFile(
                    fileInfo.getFileName(),
                    fileInfo.getFileSize(),
                    fileHash,
                    totalChunks);

            // Liên kết peer với file
            List<Integer> allChunks = new ArrayList<>();
            for (int i = 0; i < totalChunks; i++) {
                allChunks.add(i);
            }
            db.linkPeerToFile(peerDbId, fileDbId, true, allChunks);

            // Log activity
            db.logActivity(peerID, "PUBLISH", "Published file: " + fileInfo.getFileName() +
                    " (" + fileInfo.getFormattedSize() + ")");

            Message response = new Message(Message.Type.REGISTER_OK);
            response.setContent("Publish file thành công: " + fileInfo.getFileName());
            out.writeObject(response);

            System.out.println("[TRACKER] 📁 File mới: " + fileInfo.getFileName() +
                    " (DB ID: " + fileDbId + ")");
            printStatus();
        }

        private void handleUnpublish(String fileName) throws IOException {
            Set<FileInfo> files = peerFiles.get(peerID);
            if (files != null) {
                files.removeIf(f -> f.getFileName().equals(fileName));
            }
            List<FileInfo> list = fileIndex.get(fileName);
            if (list != null) {
                list.removeIf(f -> (f.getPeerIP() + ":" + f.getPeerPort()).equals(peerID));
                if (list.isEmpty())
                    fileIndex.remove(fileName);
            }

            // ⭐ XÓA KHỎI DATABASE
            db.unlinkPeerFromFile(peerID, fileName);

            // Log activity
            db.logActivity(peerID, "UNPUBLISH", "Unpublished file: " + fileName);

            Message response = new Message(Message.Type.REGISTER_OK);
            response.setContent("Unpublish thành công: " + fileName);
            out.writeObject(response);

            System.out.println("[TRACKER] 🗑️ Unpublish: " + fileName + " từ " + peerID);
            printStatus();
        }

        private void handleSearch(String keyword) throws IOException {
            // ⭐ TÌM KIẾM TỪ DATABASE (có thông tin seeds)
            List<FileInfo> results = db.searchFiles(keyword);

            // Nếu DB rỗng, fallback về RAM cache - ĐÃ BỎ ĐỂ ĐẢM BẢO TÍNH NHẤT QUÁN CỦA
            // DATABASE (hidden file không được hiện)
            /*
             * if (results.isEmpty()) {
             * for (Map.Entry<String, List<FileInfo>> entry : fileIndex.entrySet()) {
             * if (entry.getKey().toLowerCase().contains(keyword.toLowerCase())) {
             * results.addAll(entry.getValue());
             * }
             * }
             * }
             */

            // Tính seed count
            Map<String, Integer> seedCount = new HashMap<>();
            for (FileInfo f : results) {
                String hash = f.getFileHash();
                seedCount.merge(hash, 1, Integer::sum);
            }

            // Cập nhật seed count
            for (FileInfo f : results) {
                f.setSeedCount(seedCount.getOrDefault(f.getFileHash(), 1));
            }

            Message response = new Message(Message.Type.SEARCH_RESULT);
            response.setFileList(results);
            response.setContent("Tìm thấy " + results.size() + " kết quả");
            out.writeObject(response);

            System.out.println("[TRACKER] 🔍 Tìm kiếm '" + keyword + "': " + results.size() + " kết quả");
        }

        private void handleGetAllFiles() throws IOException {
            // ⭐ LẤY TỪ DATABASE
            List<FileInfo> allFiles = db.getAllFiles();

            // Nếu DB rỗng, fallback về RAM cache - ĐÃ BỎ
            /*
             * if (allFiles.isEmpty()) {
             * for (List<FileInfo> list : fileIndex.values()) {
             * allFiles.addAll(list);
             * }
             * }
             */

            // Tính seed count
            Map<String, Integer> seedCount = new HashMap<>();
            for (FileInfo f : allFiles) {
                String hash = f.getFileHash();
                seedCount.merge(hash, 1, Integer::sum);
            }
            for (FileInfo f : allFiles) {
                f.setSeedCount(seedCount.getOrDefault(f.getFileHash(), 1));
            }

            Message response = new Message(Message.Type.FILE_LIST);
            response.setFileList(allFiles);
            out.writeObject(response);

            System.out.println("[TRACKER] 📋 Gửi danh sách " + allFiles.size() + " files");
        }

        /**
         * Xử lý yêu cầu lấy danh sách nguồn cho một file (Multi-source)
         */
        private void handleGetFileSources(String fileHash) throws IOException {
            // ⭐ LẤY DANH SÁCH PEERS CÓ FILE TỪ DATABASE
            List<FileInfo> sources = db.getPeersHavingFile(fileHash);

            Message response = new Message(Message.Type.FILE_SOURCES);
            response.setFileList(sources);
            response.setContent("Tìm thấy " + sources.size() + " nguồn");
            out.writeObject(response);

            System.out.println("[TRACKER] 📡 Sources cho " + fileHash.substring(0, 8) +
                    "...: " + sources.size() + " peers");
        }

        private void sendError(String error) throws IOException {
            Message response = new Message(Message.Type.ERROR, error);
            out.writeObject(response);
        }

        private void closeConnection() {
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

    private void printStatus() {
        System.out.println("──────────────────────────────────────");
        System.out.println("Peers online (RAM): " + peerFiles.size());
        System.out.println("Total files (RAM): " + fileIndex.values().stream()
                .mapToInt(List::size).sum());
        System.out.println("──────────────────────────────────────");
    }

    public static void main(String[] args) {
        try {
            System.setOut(new java.io.PrintStream(System.out, true, "UTF-8"));
            System.setErr(new java.io.PrintStream(System.err, true, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        int port = 5000;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        new TrackerServer(port).start();
    }
}