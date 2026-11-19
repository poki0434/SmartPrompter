package com.smartprompter.app;

import android.content.Context;
import android.os.Build;
import android.os.Debug;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * 轻量级提词运行日志管理器：
 * 仅保留最近 3 次提词运行日志，每 15 秒记录一次内存/进度心跳（零帧率损耗），支持导出 TXT 排查问题。
 */
public class PrompterLogger {

    private static final int MAX_SESSIONS = 3;
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private static final SimpleDateFormat FILE_FMT = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());

    private static File currentLogFile = null;
    private static final StringBuilder buffer = new StringBuilder();

    private static File getLogDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "prompter_logs");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static synchronized void startNewSession(Context ctx, String appVersion, int scriptLen,
                                                    int speed, int fontSize, float lineSpacing,
                                                    boolean mirror, boolean aiFollow, boolean btMic) {
        flush(ctx);
        File dir = getLogDir(ctx);
        String name = "session_" + FILE_FMT.format(new Date()) + ".log";
        currentLogFile = new File(dir, name);
        trimOldSessions(dir);

        buffer.setLength(0);
        buffer.append("=== 提词会话开始 ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())).append(" ===\n");
        buffer.append("App: ").append(appVersion)
                .append(" | Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                .append(" (Android ").append(Build.VERSION.RELEASE).append(")\n");
        buffer.append("Script: ").append(scriptLen).append(" chars | Speed=").append(speed)
                .append(" | Font=").append(fontSize).append("sp | LineSpacing=").append(String.format(Locale.US, "%.2fx", lineSpacing))
                .append(" | Mirror=").append(mirror)
                .append(" | AI=").append(aiFollow)
                .append(" | BtMic=").append(btMic).append("\n");
        flush(ctx);
    }

    public static synchronized void logEvent(Context ctx, String event) {
        if (currentLogFile == null) return;
        buffer.append("[").append(TIME_FMT.format(new Date())).append("] ").append(event).append("\n");
        if (buffer.length() >= 512) {
            flush(ctx);
        }
    }

    public static synchronized void logHeartbeat(Context ctx, int progressPct) {
        if (currentLogFile == null) return;
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        long nativeMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024);
        buffer.append("[").append(TIME_FMT.format(new Date())).append("] [心跳] 进度=")
                .append(progressPct).append("% | Java堆=").append(usedMb).append("/").append(maxMb)
                .append("MB | Native堆=").append(nativeMb).append("MB\n");
        flush(ctx);
    }

    public static synchronized void endSession(Context ctx, String reason) {
        if (currentLogFile == null) return;
        logEvent(ctx, "会话结束: " + reason);
        flush(ctx);
        currentLogFile = null;
    }

    public static synchronized void flush(Context ctx) {
        if (currentLogFile == null || buffer.length() == 0) return;
        try (FileOutputStream fos = new FileOutputStream(currentLogFile, true)) {
            fos.write(buffer.toString().getBytes(StandardCharsets.UTF_8));
            fos.flush();
            buffer.setLength(0);
        } catch (Exception ignored) {}
    }

    private static void trimOldSessions(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".log"));
        if (files == null || files.length < MAX_SESSIONS) return;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        int toDelete = files.length - (MAX_SESSIONS - 1);
        for (int i = 0; i < toDelete; i++) {
            // 保留最新的 2 个旧文件 + 即将创建的 1 个新文件 = 共 3 次记录
            files[i].delete();
        }
    }

    public static synchronized String getRecentLogsCombined(Context ctx) {
        flush(ctx);
        File dir = getLogDir(ctx);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".log"));
        if (files == null || files.length == 0) {
            return "暂无提词运行日志（开始一次提词后会自动记录最近 3 次会话）。";
        }
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName())); // 最新的在前
        StringBuilder sb = new StringBuilder();
        int count = Math.min(MAX_SESSIONS, files.length);
        for (int i = 0; i < count; i++) {
            sb.append("----------------------------------------\n");
            sb.append("记录 #").append(i + 1).append(" (").append(files[i].getName()).append(")\n");
            sb.append("----------------------------------------\n");
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(files[i]), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            } catch (Exception e) {
                sb.append("读取失败: ").append(e.getMessage()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public static synchronized void clearAllLogs(Context ctx) {
        currentLogFile = null;
        buffer.setLength(0);
        File dir = getLogDir(ctx);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
    }
}
