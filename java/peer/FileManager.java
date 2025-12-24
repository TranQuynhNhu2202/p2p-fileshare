package peer;

import tracker.FileInfo;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Quản lý files cục bộ của peer
 */
public class FileManager {
    private String sharedFolder; // Thư mục chia sẻ
    private String downloadFolder; // Thư mục tải về
    private Map<String, File> sharedFiles;

    public FileManager(String sharedFolder, String downloadFolder) {
        this.sharedFolder = sharedFolder;
        this.downloadFolder = downloadFolder;
        this.sharedFiles = new HashMap<>();

        // Tạo thư mục nếu chưa tồn tại
        new File(sharedFolder).mkdirs();
        new File(downloadFolder).mkdirs();

        // Scan thư mục shared
        scanSharedFolder();
    }

    /**
     * Quét thư mục shared để lấy danh sách files
     */
    public void scanSharedFolder() {
        sharedFiles.clear();
        File folder = new File(sharedFolder);
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    sharedFiles.put(file.getName(), file);
                }
            }
        }
        System.out.println("[FileManager] Đã scan " + sharedFiles.size() + " files trong thư mục shared");
    }

    /**
     * Lấy danh sách FileInfo để publish lên tracker
     */
    public List<FileInfo> getSharedFileInfos(String peerIP, int peerPort) {
        List<FileInfo> list = new ArrayList<>();
        for (File file : sharedFiles.values()) {
            FileInfo info = new FileInfo(file.getName(), file.length(), peerIP, peerPort);
            // ⭐ QUAN TRỌNG: Tính MD5 hash cho mỗi file
            String hash = calculateFileHash(file.getName());
            info.setFileHash(hash);
            list.add(info);
        }
        return list;
    }

    /**
     * Kiểm tra file có tồn tại không
     */
    public boolean hasFile(String fileName) {
        return sharedFiles.containsKey(fileName);
    }

    /**
     * Lấy File object
     */
    public File getFile(String fileName) {
        return sharedFiles.get(fileName);
    }

    /**
     * Đọc file thành byte array
     */
    public byte[] readFile(String fileName) throws IOException {
        File file = sharedFiles.get(fileName);
        if (file == null)
            return null;

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            return data;
        }
    }

    /**
     * Đọc một phần file (cho download từng chunk)
     */
    public byte[] readFileChunk(String fileName, long offset, int chunkSize) throws IOException {
        File file = sharedFiles.get(fileName);
        if (file == null)
            return null;

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            int remaining = (int) Math.min(chunkSize, file.length() - offset);
            byte[] chunk = new byte[remaining];
            raf.readFully(chunk);
            return chunk;
        }
    }

    /**
     * Lưu file đã tải về
     */
    /**
     * Lưu file đã tải về (Mặc định vào download folder)
     */
    public void saveFile(String fileName, byte[] data) throws IOException {
        saveFile(fileName, data, null);
    }

    /**
     * Lưu file đã tải về vào đường dẫn tùy chọn
     */
    public void saveFile(String fileName, byte[] data, String customPath) throws IOException {
        File file = getTargetFile(fileName, customPath);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }
        System.out.println("[FileManager] Đã lưu file: " + file.getAbsolutePath());

        // Thêm vào danh sách shared để có thể chia sẻ lại (nếu file nằm trong thư mục
        // shared/download của app)
        // Tuy nhiên với custom path bên ngoài, ta có thể chọn không add vào sharedFiles
        // hoặc add tùy logic.
        // Ở đây ta cứ add vào để tracking nếu user chọn lưu vào folder quản lý.
        // Check using absolute paths to avoid relative vs absolute mismatch
        // ("downloads" vs "C:\...\downloads")
        File parent = file.getParentFile();
        if (parent != null) {
            String parentPath = parent.getAbsolutePath();
            String downloadPath = new File(downloadFolder).getAbsolutePath();
            String sharedPath = new File(sharedFolder).getAbsolutePath();

            if (parentPath.equals(downloadPath) || parentPath.equals(sharedPath)) {
                sharedFiles.put(fileName, file);
                System.out.println("[FileManager] Added to shared files: " + fileName);
            }
        }
    }

    /**
     * Lưu file theo chunks (cho download lớn)
     */
    /**
     * Lưu file theo chunks
     */
    public void saveFileChunk(String fileName, byte[] data, long offset, boolean append) throws IOException {
        saveFileChunk(fileName, data, offset, null);
    }

    public void saveFileChunk(String fileName, byte[] data, long offset, String customPath) throws IOException {
        File file = getTargetFile(fileName, customPath);
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(offset);
            raf.write(data);
        }
    }

    /**
     * Hoàn tất việc tải file - thêm vào danh sách shared
     */
    public void finalizeDownload(String fileName, String customPath) {
        File file = getTargetFile(fileName, customPath);
        if (file.exists()) {
            if (file.getParent().equals(new File(downloadFolder).getAbsolutePath()) ||
                    file.getParent().equals(new File(sharedFolder).getAbsolutePath())) {
                sharedFiles.put(fileName, file);
            }
            System.out.println("[FileManager] File đã tải hoàn tất: " + file.getAbsolutePath());
        }
    }

    /**
     * Thêm file đã tải vào danh sách chia sẻ
     */
    public void addDownloadedFile(File file) {
        if (file.exists()) {
            sharedFiles.put(file.getName(), file);
            System.out.println("[FileManager] Thêm file đã tải vào danh sách chia sẻ: " + file.getName());
        }
    }

    /**
     * Thêm file mới vào shared folder
     */
    public FileInfo addFileToShare(File sourceFile, String peerIP, int peerPort) throws IOException {
        // Copy file vào shared folder
        File destFile = new File(sharedFolder, sourceFile.getName());

        try (FileInputStream fis = new FileInputStream(sourceFile);
                FileOutputStream fos = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }

        sharedFiles.put(destFile.getName(), destFile);

        // ⭐ QUAN TRỌNG: Tính hash để đảm bảo file được nhận dạng đúng trên toàn hệ
        // thống
        FileInfo info = new FileInfo(destFile.getName(), destFile.length(), peerIP, peerPort);
        String hash = calculateFileHash(destFile.getName());
        info.setFileHash(hash);
        System.out.println("[FileManager] File hash for " + destFile.getName() + ": " + hash);

        return info;
    }

    /**
     * Xóa file khỏi danh sách shared
     */
    public void removeFromShare(String fileName) {
        sharedFiles.remove(fileName);
        System.out.println("[FileManager] Đã xóa khỏi danh sách chia sẻ: " + fileName);
    }

    /**
     * Xóa file khỏi danh sách shared VÀ xóa file vật lý
     */
    public boolean removeAndDeleteFile(String fileName) {
        File file = sharedFiles.remove(fileName);
        if (file != null && file.exists()) {
            boolean deleted = file.delete();
            System.out.println("[FileManager] Xóa file vật lý " + fileName + ": " + (deleted ? "OK" : "FAILED"));
            return deleted;
        }
        return false;
    }

    /**
     * Xóa file tạm (.tmp) trong thư mục downloads
     */
    public boolean deleteTempFile(String fileName) {
        File tempFile = new File(downloadFolder, fileName + ".tmp");
        if (tempFile.exists()) {
            boolean deleted = tempFile.delete();
            System.out.println("[FileManager] Xóa file tạm " + fileName + ".tmp: " + (deleted ? "OK" : "FAILED"));
            return deleted;
        }
        return false; // Added return statement for the case where tempFile does not exist
    }

    /**
     * Lấy file đích dựa trên tên và đường dẫn tùy chọn
     */
    public File getTargetFile(String fileName, String customPath) {
        if (customPath != null && !customPath.isEmpty()) {
            // Nếu customPath là thư mục -> nối fileName vào
            File path = new File(customPath);
            if (path.isDirectory()) {
                return new File(path, fileName);
            }
            // Nếu customPath là file (có đuôi mở rộng) -> dùng luôn (ít gặp trong case này
            // vì ta chọn folder)
            return path;
        }
        return new File(downloadFolder, fileName);
    }

    /**
     * Tính MD5 hash của file
     */
    public String calculateFileHash(String fileName) {
        try {
            File file = sharedFiles.get(fileName);
            if (file == null)
                return null;

            MessageDigest md = MessageDigest.getInstance("MD5");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    md.update(buffer, 0, bytesRead);
                }
            }

            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // Getters
    public String getSharedFolder() {
        return sharedFolder;
    }

    public String getDownloadFolder() {
        return downloadFolder;
    }

    public void setDownloadFolder(String downloadFolder) {
        this.downloadFolder = downloadFolder;
        new File(downloadFolder).mkdirs();
        System.out.println("[FileManager] Đã đổi thư mục download: " + downloadFolder);
    }

    public Map<String, File> getSharedFiles() {
        return sharedFiles;
    }

    public int getSharedFileCount() {
        return sharedFiles.size();
    }
}