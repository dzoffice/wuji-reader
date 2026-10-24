package com.wuji.reader.core;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 后台取数 / 主线程回UI 的最小调度器 */
public final class Tasks {

    public static final ExecutorService IO = Executors.newCachedThreadPool();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private Tasks() {
    }

    public static void io(Runnable r) {
        IO.execute(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                // 后台兜底：个别书源超大页面解析 OOM 等 Error 不再杀死整个进程，
                // 堆栈写入 crash 日志便于排查；页面停留在"加载中"，用户可重试。
                CrashGuard.log("IO-" + t.getClass().getSimpleName(), t);
            }
        });
    }

    public static void ui(Runnable r) {
        UI.post(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                // 主线程兜底：UI 回调里的空指针/越界/OOM 会直接杀死进程
                // （catch(Exception) 抓不住 Error），这里统一吃掉并留日志。
                CrashGuard.log("UI-" + t.getClass().getSimpleName(), t);
            }
        });
    }
}
