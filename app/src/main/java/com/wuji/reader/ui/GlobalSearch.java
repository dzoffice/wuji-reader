package com.wuji.reader.ui;

import com.wuji.reader.model.Item;
import com.wuji.reader.model.Result;
import com.wuji.reader.source.BookSource;
import com.wuji.reader.source.ComicSource;
import com.wuji.reader.source.SourceManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 全局跨源搜索：并发搜全部生效源，按源顺序合并结果。
 *
 * 约束：
 *   - 固定线程池，最多 6 个；
 *   - 单源 Future 超时 12s，超时记入失败列表；
 *   - 每源最多取前 10 条；
 *   - 同 sourceId+title 去重；
 *   - 每个 Item 回填 sourceName（源显示名）与 sourceId。
 *
 * 回调在内部工作线程上执行，不碰主线程；由调用方自行切主线程刷新 UI。
 */
public final class GlobalSearch {

    public interface Callback {
        void onDone(List<Item> merged, List<String> fails);
    }

    private static final int MAX_THREADS = 6;
    private static final long TIMEOUT_SEC = 12;
    private static final int PER_SOURCE_MAX = 10;

    private GlobalSearch() {
    }

    public static void search(boolean isBook, String q, final Callback cb) {
        if (cb == null) {
            return;
        }
        final String keyword = q == null ? "" : q.trim();
        new Thread(() -> {
            final List<String> fails = new ArrayList<>();
            List<Item> merged = new ArrayList<>();
            try {
                if (isBook) {
                    merged = runBooks(SourceManager.books(), keyword, fails);
                } else {
                    merged = runComics(SourceManager.comics(), keyword, fails);
                }
            } catch (Throwable t) {
                fails.add("全局搜索调度失败："
                        + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            }
            cb.onDone(merged, fails);
        }).start();
    }

    private static List<Item> runBooks(List<BookSource> sources, String keyword,
                                       List<String> fails) {
        if (sources == null || sources.isEmpty()) {
            return new ArrayList<>();
        }
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(MAX_THREADS, sources.size()));
        List<Future<List<Item>>> futures = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (final BookSource s : sources) {
            names.add(s.getName());
            futures.add(pool.submit(() -> {
                Result<Item> r = s.search(keyword, 1);
                List<Item> out = new ArrayList<>();
                if (r != null && r.list != null) {
                    int n = Math.min(PER_SOURCE_MAX, r.list.size());
                    for (int i = 0; i < n; i++) {
                        Item it = r.list.get(i);
                        it.sourceName = s.getName();
                        it.sourceId = s.getId();
                        out.add(it);
                    }
                }
                return out;
            }));
        }
        return collect(pool, futures, names, fails);
    }

    private static List<Item> runComics(List<ComicSource> sources, String keyword,
                                        List<String> fails) {
        if (sources == null || sources.isEmpty()) {
            return new ArrayList<>();
        }
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(MAX_THREADS, sources.size()));
        List<Future<List<Item>>> futures = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (final ComicSource s : sources) {
            names.add(s.getName());
            futures.add(pool.submit(() -> {
                Result<Item> r = s.search(keyword, 1);
                List<Item> out = new ArrayList<>();
                if (r != null && r.list != null) {
                    int n = Math.min(PER_SOURCE_MAX, r.list.size());
                    for (int i = 0; i < n; i++) {
                        Item it = r.list.get(i);
                        it.sourceName = s.getName();
                        it.sourceId = s.getId();
                        out.add(it);
                    }
                }
                return out;
            }));
        }
        return collect(pool, futures, names, fails);
    }

    private static List<Item> collect(ExecutorService pool,
                                      List<Future<List<Item>>> futures,
                                      List<String> names,
                                      List<String> fails) {
        List<Item> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < futures.size(); i++) {
            Future<List<Item>> f = futures.get(i);
            String name = i < names.size() ? names.get(i) : "未知源";
            try {
                List<Item> part = f.get(TIMEOUT_SEC, TimeUnit.SECONDS);
                if (part != null) {
                    for (Item it : part) {
                        String title = it.title == null ? "" : it.title;
                        String key = (it.sourceId == null ? "" : it.sourceId)
                                + "\u0001" + title;
                        if (seen.add(key)) {
                            merged.add(it);
                        }
                    }
                }
            } catch (TimeoutException e) {
                f.cancel(true);
                fails.add(name + "：超时（>" + TIMEOUT_SEC + "s）");
            } catch (ExecutionException e) {
                Throwable c = e.getCause();
                String m = c == null ? "未知错误"
                        : (c.getMessage() == null || c.getMessage().isEmpty()
                        ? c.getClass().getSimpleName() : c.getMessage());
                fails.add(name + "：" + m);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fails.add(name + "：全局搜索被中断");
            }
        }
        pool.shutdown();
        return merged;
    }
}
