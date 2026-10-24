package com.wuji.reader.core;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃兜底：把未捕获异常的堆栈写到文件，供离线排查墨水屏问题。
 *
 * 日志位置（优先外存应用私有目录，无需存储权限）：
 *   /sdcard/Android/data/com.wuji.reader/files/crash-<时间戳>.log
 * 外存不可用时退到内部 files/crash-<时间戳>.log。
 *
 * 用法：
 *   - App.onCreate 里 install(this)：主线程未捕获异常先落盘再交系统默认处理；
 *   - Tasks.io 等后台兜底调 log(tag, t)：只记录、不杀进程。
 *
 * 排查：崩溃后用文件管理器（或 adb pull）取
 *   /sdcard/Android/data/com.wuji.reader/files/ 下最新的 crash-*.log。
 */
public final class CrashGuard {

    private static File logDir;

    private CrashGuard() {
    }

    public static void install(Context ctx) {
        File ext = ctx.getExternalFilesDir(null);
        logDir = ext != null ? ext : ctx.getFilesDir();
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                append("CRASH thread=" + t.getName(), e);
            } catch (Throwable ignored) {
            }
            if (prev != null) {
                prev.uncaughtException(t, e);
            }
        });
    }

    /** 记录但不中断进程（后台线程兜底用） */
    public static void log(String tag, Throwable e) {
        try {
            append(tag, e);
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void append(String tag, Throwable e) {
        if (logDir == null || e == null) {
            return;
        }
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        StringBuilder sb = new StringBuilder();
        sb.append("==== ").append(tag).append(" ====\n");
        sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append('\n');
        sb.append(sw).append('\n');
        FileWriter w = null;
        try {
            File[] old = logDir.listFiles((d, n) -> n.startsWith("crash-"));
            if (old != null && old.length >= 5) {
                Arrays.sort(old, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return Long.compare(a.lastModified(), b.lastModified());
                    }
                });
                old[0].delete();
            }
            w = new FileWriter(new File(logDir,
                    "crash-" + System.currentTimeMillis() + ".log"));
            w.write(sb.toString());
            w.flush();
        } catch (Throwable ignored) {
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
