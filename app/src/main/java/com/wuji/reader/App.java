package com.wuji.reader;

import android.app.Application;

import com.wuji.reader.core.CrashGuard;
import com.wuji.reader.source.SourceManager;
import com.wuji.reader.source.SourceRegistry;

/**
 * 进程入口。
 *
 * 发布版不内置任何书源：SourceRegistry 的 BOOKS / COMICS 为空，
 * 源由用户在「设置 → 源管理」导入 Legado 书源 JSON 后，
 * 由 SourceManager 叠加启用/排序/自定义（prefs "source_mgr"）。
 * JSON 损坏时 LegadoSourceLoader 内部吞掉异常，不影响启动。
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 全局崩溃兜底：堆栈落盘 /sdcard/Android/data/com.wuji.reader/files/
        CrashGuard.install(this);
        SourceRegistry.init(this);
        SourceManager.init(this);
    }
}
