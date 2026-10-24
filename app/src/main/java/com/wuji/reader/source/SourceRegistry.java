package com.wuji.reader.source;

import android.content.Context;

import com.wuji.reader.legado.LegadoSourceLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * 源注册表（发布版）。
 *
 * 发布版不含内置源：BOOKS / COMICS 保持为空，
 * 全部源通过 设置 → 源管理 导入 Legado 书源 JSON 获得。
 *
 * 说明：
 *   - 本工程历史上的手写内置源（酷喔、笔趣阁、七猫、武芊漫画等）
 *     与 Legado 纯规则源均已从发布包中移除，
 *     不再随 APK 分发任何站点地址与解析规则；
 *   - init() 仍会尝试读取 assets/sources 下的 Legado 源，
 *     发布版该目录已不存在，此时静默返回空列表，绝不影响启动；
 *   - install() 保留给将来需要内置源的构建分支，发布版不会被调用到。
 */
public final class SourceRegistry {

    public static final List<BookSource> BOOKS = new ArrayList<>();
    public static final List<ComicSource> COMICS = new ArrayList<>();

    private static boolean legadoLoaded = false;

    private SourceRegistry() {
    }

    /**
     * 读取 assets 里的 Legado 书源。由 App.onCreate 调用；
     * 失败只记录，不影响已注册的手写源。
     */
    public static synchronized void init(Context context) {
        if (legadoLoaded || context == null) {
            return;
        }
        legadoLoaded = true;
        List<BookSource> books = new ArrayList<>();
        List<ComicSource> comics = new ArrayList<>();
        try {
            books = LegadoSourceLoader.loadBooks(context.getAssets());
        } catch (Throwable ignored) {
        }
        try {
            comics = LegadoSourceLoader.loadComics(context.getAssets());
        } catch (Throwable ignored) {
        }
        install(books, comics);
    }

    /**
     * 把 Legado 源追加到注册表：
     *   BOOKS 追加；
     *   COMICS 追加（发布版无内置源，插到哪里都是末尾）。
     *   发布版 assets/sources 不存在，传入的均为空列表，此方法为空操作。
     */
    public static synchronized void install(List<BookSource> books, List<ComicSource> comics) {
        if (books != null) {
            BOOKS.addAll(books);
        }
        if (comics != null && !comics.isEmpty()) {
            COMICS.addAll(comics);
        }
    }
}
