package peer;

import database.DatabaseManager;
import database.DatabaseManager.DownloadState;
import protocol.Message;
import tracker.FileInfo;

import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multi-Source Downloader với Resume Support
 * - Tải song song từ nhiều Peer
 * - Hỗ trợ tiếp tục khi bị gián đoạn
 * - Phân phối chunks thông minh
 */
public class MultiSourceDownloader {

    private static final int CHUNK_SIZE = 64 * 1024; // 64KB
    private static final int MAX_CONCURRENT_SOURCES = 5;
    private static final int MAX_RETRIES = 3;

    private final String localPeerId;
    private final FileManager fileManager;
    private final DatabaseManager db;
    private final ExecutorService executor;

    // Download state
    private volatile boolean isPaused = false;
    private volatile boolean isCancelled = false;

    // Callbacks
    private DownloadCallback callback;

    public interface DownloadCallback {
        void onDownloadStarted(String fileName, int totalSources);

        void onProgress(String fileName, int percent, long downloaded, long total, double speed);

        void onSourceStatus(String sourceIp, int port, String status, int chunksCompleted);

        void onCompleted(String fileName);

        void onFailed(String fileName, String error);

        void onPaused(String fileName, int percent);

        void onResumed(String fileName, int percent);
    }

    public MultiSourceDownloader(String localPeerId, FileManager fileManager) {
        this.localPeerId = localPeerId;
        this.fileManager = fileManager;
        this.db = DatabaseManager.getInstance();
        this.executor = Executors.newFixedThreadPool(MAX_CONCURRENT_SOURCES + 2);
    }

    public void setCallback(DownloadCallback callback) {
        this.callback = callback;
    }

    /**
     * Tải file từ nhiều nguồn với hỗ trợ resume
     */
    public CompletableFuture<Boolean> downloadFile(FileInfo fileInfo) {
        return downloadFile(fileInfo, null);
    }

    public CompletableFuture<Boolean> downloadFile(FileInfo fileInfo, String savePath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return performDownload(fileInfo, savePath);
            } catch (Exception e) {
                e.printStackTrace();
                if (callback != null)
                    callback.onFailed(fileInfo.getFileName(), e.getMessage());
                return false;
            }
        }, executor);
    }

    private boolean performDownload(FileInfo fileInfo, String savePath) throws Exception {
        String fileName = fileInfo.getFileName();
        String fileHash = fileInfo.getFileHash();
        long fileSize = fileInfo.getFileSize();
        int totalChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);

        // Kiểm tra download state cũ (resume)
        DownloadState state = db.getDownloadState(localPeerId, fileHash);
        Set<Integer> completedChunks;
        int downloadId;

        if (state != null && state.status.equals("paused")) {
            // Resume download
            downloadId = state.downloadId;
            completedChunks = new ConcurrentSkipListSet<>(state.completedChunks);
            System.out.println("[Download] Tiếp tục tải " + fileName + " từ " +
                    completedChunks.size() + "/" + totalChunks + " chunks");
            if (callback != null) {
                int percent = (int) (completedChunks.size() * 100 / totalChunks);
                callback.onResumed(fileName, percent);
            }
        } else {
            // New download
            downloadId = db.createDownload(fileInfo.getFileDbId(), localPeerId,
                    fileName, fileSize, totalChunks);
            completedChunks = new ConcurrentSkipListSet<>();
        }

        // Lấy danh sách tất cả peer có file này
        List<FileInfo> sources = db.getPeersHavingFile(fileHash);
        if (sources.isEmpty()) {
            // Fallback: sử dụng source ban đầu
            sources = new ArrayList<>();
            sources.add(fileInfo);
        }

        System.out.println("[Download] Tìm thấy " + sources.size() + " nguồn cho " + fileName);
        if (callback != null)
            callback.onDownloadStarted(fileName, sources.size());

        // Tạo file tạm
        File targetFile = fileManager.getTargetFile(fileName, savePath);
        // Lưu file tạm cùng thư mục với target file để dễ rename
        File tempFile = new File(targetFile.getParent(), fileName + ".tmp");

        // Đảm bảo thư mục cha tồn tại
        if (!tempFile.getParentFile().exists()) {
            tempFile.getParentFile().mkdirs();
        }

        RandomAccessFile raf = new RandomAccessFile(tempFile, "rw");
        raf.setLength(fileSize);

        // Tracking
        AtomicLong downloadedBytes = new AtomicLong(completedChunks.size() * CHUNK_SIZE);
        long startTime = System.currentTimeMillis();

        // Tạo queue chunks cần tải
        Queue<Integer> pendingChunks = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < totalChunks; i++) {
            if (!completedChunks.contains(i)) {
                pendingChunks.add(i);
            }
        }

        // Tạo các worker cho mỗi source
        int numSources = Math.min(sources.size(), MAX_CONCURRENT_SOURCES);
        CountDownLatch latch = new CountDownLatch(numSources);
        List<Future<?>> workers = new ArrayList<>();

        for (int i = 0; i < numSources; i++) {
            FileInfo source = sources.get(i % sources.size());
            Future<?> worker = executor.submit(() -> {
                try {
                    downloadWorker(source, fileName, fileSize, totalChunks,
                            pendingChunks, completedChunks, raf,
                            downloadedBytes, downloadId);
                } finally {
                    latch.countDown();
                }
            });
            workers.add(worker);
        }

        // Progress reporter
        executor.submit(() -> {
            while (!pendingChunks.isEmpty() && !isCancelled && !isPaused) {
                try {
                    Thread.sleep(500);
                    int percent = (int) (completedChunks.size() * 100 / totalChunks);
                    long elapsed = System.currentTimeMillis() - startTime;
                    double speed = elapsed > 0 ? downloadedBytes.get() * 1000.0 / elapsed : 0;

                    if (callback != null) {
                        callback.onProgress(fileName, percent, downloadedBytes.get(), fileSize, speed);
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        });

        // Đợi hoàn thành
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        raf.close();

        // Kiểm tra kết quả
        if (isCancelled) {
            tempFile.delete();
            return false;
        }

        if (isPaused) {
            db.pauseDownload(downloadId);
            if (callback != null) {
                int percent = (int) (completedChunks.size() * 100 / totalChunks);
                callback.onPaused(fileName, percent);
            }
            return false;
        }

        if (completedChunks.size() == totalChunks) {
            // Hoàn thành - đổi tên file
            File finalFile = fileManager.getTargetFile(fileName, savePath);
            if (finalFile.exists())
                finalFile.delete();
            tempFile.renameTo(finalFile);

            // Cập nhật database
            db.completeDownload(downloadId);

            // Chỉ add vào shared nếu nằm trong thư mục quản lý
            fileManager.finalizeDownload(fileName, savePath);

            System.out.println("[Download] Hoàn thành: " + fileName);
            if (callback != null)
                callback.onCompleted(fileName);
            return true;
        } else {
            // Chưa hoàn thành - có thể retry
            db.updateDownloadProgress(downloadId, completedChunks, downloadedBytes.get());
            if (callback != null) {
                callback.onFailed(fileName, "Chỉ tải được " + completedChunks.size() + "/" + totalChunks + " chunks");
            }
            return false;
        }
    }

    /**
     * Worker tải chunks từ một source
     */
    private void downloadWorker(FileInfo source, String fileName, long fileSize, int totalChunks,
            Queue<Integer> pendingChunks, Set<Integer> completedChunks,
            RandomAccessFile raf, AtomicLong downloadedBytes, int downloadId) {

        String sourceId = source.getPeerIP() + ":" + source.getPeerPort();
        int chunksDownloaded = 0;
        int retries = 0;

        while (!pendingChunks.isEmpty() && !isCancelled && !isPaused && retries < MAX_RETRIES) {
            Integer chunkIndex = pendingChunks.poll();
            if (chunkIndex == null)
                break;

            // Kiểm tra chunk đã được tải bởi worker khác chưa
            if (completedChunks.contains(chunkIndex))
                continue;

            try {
                byte[] chunkData = downloadChunk(source, fileName, chunkIndex, fileSize);

                if (chunkData != null) {
                    // Ghi vào file
                    synchronized (raf) {
                        long offset = (long) chunkIndex * CHUNK_SIZE;
                        raf.seek(offset);
                        raf.write(chunkData);
                    }

                    completedChunks.add(chunkIndex);
                    downloadedBytes.addAndGet(chunkData.length);
                    chunksDownloaded++;
                    retries = 0;

                    // Cập nhật DB định kỳ
                    if (chunksDownloaded % 10 == 0) {
                        db.updateDownloadProgress(downloadId, completedChunks, downloadedBytes.get());
                    }

                    if (callback != null) {
                        callback.onSourceStatus(source.getPeerIP(), source.getPeerPort(),
                                "active", chunksDownloaded);
                    }
                } else {
                    // Chunk tải thất bại - đưa lại vào queue
                    pendingChunks.add(chunkIndex);
                    retries++;
                }
            } catch (Exception e) {
                pendingChunks.add(chunkIndex);
                retries++;
                System.err.println("[Worker " + sourceId + "] Lỗi chunk " + chunkIndex + ": " + e.getMessage());
            }
        }

        if (callback != null) {
            String status = retries >= MAX_RETRIES ? "failed" : "completed";
            callback.onSourceStatus(source.getPeerIP(), source.getPeerPort(), status, chunksDownloaded);
        }
    }

    /**
     * Tải một chunk từ peer
     */
    private byte[] downloadChunk(FileInfo source, String fileName, int chunkIndex, long fileSize) {
        try (Socket socket = new Socket(source.getPeerIP(), source.getPeerPort());
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            socket.setSoTimeout(30000);

            // Gửi yêu cầu chunk
            Message request = new Message(Message.Type.REQUEST_CHUNK);
            request.setContent(fileName);
            request.setChunkIndex(chunkIndex);
            request.setOffset((long) chunkIndex * CHUNK_SIZE);

            int chunkSize = (int) Math.min(CHUNK_SIZE, fileSize - (long) chunkIndex * CHUNK_SIZE);
            request.setChunkSize(chunkSize);

            out.writeObject(request);
            out.flush();

            // Nhận chunk
            Message response = (Message) in.readObject();

            if (response.getType() == Message.Type.CHUNK_DATA) {
                return response.getData();
            }

        } catch (Exception e) {
            System.err.println("[Chunk] Lỗi tải chunk " + chunkIndex + " từ " +
                    source.getPeerIP() + ": " + e.getMessage());
        }
        return null;
    }

    // ==================== CONTROL METHODS ====================

    public void pause() {
        isPaused = true;
    }

    public void resume(FileInfo fileInfo) {
        isPaused = false;
        downloadFile(fileInfo);
    }

    public void cancel() {
        isCancelled = true;
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
