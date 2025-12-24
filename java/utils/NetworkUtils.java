package utils;

import java.net.*;
import java.util.*;

/**
 * Utility class để lấy đúng IP LAN của máy
 */
public class NetworkUtils {

    /**
     * Lấy IP LAN thực của máy (không phải 127.0.0.1)
     */
    /**
     * Lấy IP LAN thực của máy (ưu tiên interface có kết nối internet/router)
     */
    public static String getLocalIPAddress() {
        // Cách 1: Thử kết nối UDP đến Google DNS để xem đường đi (route) nào được chọn
        // Cách này chính xác nhất vì OS sẽ chọn interface có metric tốt nhất (thường là
        // Wifi/Ethernet)
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
            String ip = socket.getLocalAddress().getHostAddress();
            if (!ip.startsWith("127.") && !ip.equals("0.0.0.0")) {
                System.out.println("[Network] IP (Smart Selection): " + ip);
                return ip;
            }
        } catch (Exception e) {
            System.err.println("[Network] Không thể tự động detect route: " + e.getMessage());
        }

        // Cách 2: Duyệt danh sách, ưu tiên Ethernet/Wifi, tránh VMware/VirtualBox
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            String bestIp = null;
            int maxScore = -999;

            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();

                // Bỏ qua loopback và down
                if (iface.isLoopback() || !iface.isUp())
                    continue;

                String name = iface.getDisplayName().toLowerCase();
                int score = 0;

                // Tính điểm ưu tiên
                if (name.contains("wi-fi") || name.contains("wireless") || name.contains("wlan"))
                    score += 10;
                if (name.contains("ethernet") && !name.contains("vethernet"))
                    score += 5; // vEthernet thường là Hyper-V
                if (name.contains("vmware") || name.contains("virtual") || name.contains("pseudo"))
                    score -= 10;

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (!ip.startsWith("127.")) {
                            System.out.println("[Network] Candidate: " + ip + " (" + iface.getDisplayName()
                                    + ") [Score: " + score + "]");
                            if (bestIp == null || score > maxScore) {
                                bestIp = ip;
                                maxScore = score;
                            }
                        }
                    }
                }
            }

            if (bestIp != null) {
                System.out.println("[Network] Selected Best IP: " + bestIp);
                return bestIp;
            }

        } catch (SocketException e) {
            e.printStackTrace();
        }

        // Fallback cuối cùng
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }

    /**
     * Lấy tất cả IP của máy
     */
    public static List<String> getAllIPAddresses() {
        List<String> ips = new ArrayList<>();

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();

                if (!iface.isUp())
                    continue;

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        ips.add(addr.getHostAddress() + " (" + iface.getDisplayName() + ")");
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }

        return ips;
    }

    /**
     * Kiểm tra kết nối đến host:port
     */
    public static boolean testConnection(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Hiển thị thông tin mạng
     */
    public static void printNetworkInfo() {
        System.out.println("═══════════════════════════════════════════");
        System.out.println("           THÔNG TIN MẠNG                  ");
        System.out.println("═══════════════════════════════════════════");
        System.out.println("IP LAN chính: " + getLocalIPAddress());
        System.out.println("Tất cả IP:");
        for (String ip : getAllIPAddresses()) {
            System.out.println("  - " + ip);
        }
        System.out.println("═══════════════════════════════════════════");
    }

    public static void main(String[] args) {
        printNetworkInfo();
    }
}