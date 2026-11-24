package com.foxssh.desktop;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * فقط یک نمونه از برنامه به‌ازای هر کاربر: نمونهٔ اول قفل فایل می‌گیرد و روی لوپ‌بک گوش می‌دهد؛
 * اجرای دوباره (مثلاً از منوی برنامه‌ها) فقط دستور «show» به نمونهٔ اول می‌فرستد و خارج می‌شود،
 * پس آیکون سینی تکراری ساخته نمی‌شود.
 */
final class SingleInstance {
    private static FileChannel channel;  // تا پایان برنامه باز می‌ماند (قفل با بسته‌شدن آزاد می‌شود)
    private static FileLock lock;
    private static ServerSocket server;

    private SingleInstance() {
    }

    private static Path configDir() {
        return Platform.configDir();
    }

    /**
     * true = این نمونهٔ اول است و ادامه می‌دهد؛ false = نمونهٔ دیگری در حال اجراست (به آن اطلاع داده شد) و باید خارج شد.
     * quiet: برای اجرای پس‌زمینه (--hidden) به نمونهٔ در حال اجرا پیام «show» نمی‌فرستد.
     */
    static boolean acquire(boolean quiet, Runnable onShow) {
        try {
            Path dir = configDir();
            Files.createDirectories(dir);
            Path portFile = dir.resolve("instance.port");
            channel = FileChannel.open(dir.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                if (!quiet) notifyRunning(portFile);
                return false;
            }
            server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
            Files.write(portFile, String.valueOf(server.getLocalPort()).getBytes(StandardCharsets.UTF_8));
            Thread t = new Thread(SingleInstance::serve, "single-instance");
            t.setDaemon(true);
            SingleInstance.onShow = onShow;
            t.start();
            return true;
        } catch (Exception e) {
            System.err.println("[fox] single-instance: " + e);
            return true; // اگر قفل کار نکرد، مانع اجرای برنامه نشو
        }
    }

    private static volatile Runnable onShow;

    private static void serve() {
        while (!server.isClosed()) {
            try (Socket s = server.accept();
                 BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
                s.setSoTimeout(2000);
                String line = r.readLine();
                Runnable cb = onShow;
                if ("show".equals(line) && cb != null) cb.run();
            } catch (IOException ignored) {
            }
        }
    }

    private static void notifyRunning(Path portFile) {
        for (int i = 0; i < 30; i++) { // نمونهٔ اول ممکن است هنوز در حال بالا آمدن باشد
            try {
                int port = Integer.parseInt(new String(Files.readAllBytes(portFile), StandardCharsets.UTF_8).trim());
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
                    OutputStream o = s.getOutputStream();
                    o.write("show\n".getBytes(StandardCharsets.UTF_8));
                    o.flush();
                    return;
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
