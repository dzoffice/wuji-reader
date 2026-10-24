package com.wuji.reader.news;

import com.wuji.reader.core.SourceKit;
import com.wuji.reader.source.SourceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 新闻热榜客户端。
 *
 * 复刻 wuji（packages/hot-api）的四条实战经验，不要自作聪明"优化"：
 *   1. 榜单顺序沿用上游（见 NewsRegistry），知乎在首位；
 *   2. 分批并发 6、单榜超时 10s、总预算 30s，超时就带着已拿到的部分收尾；
 *   3. 渐进渲染：每批（6 个）回来先回调一次，别等全部完成——
 *      墨水屏上"先出画面"比"一次到位"重要；
 *   4. 失败必须可诊断：HTTP 码 / 单榜超时 / 非 JSON / 结构不含 data
 *      都会写进 HotBoard.failReason，老设备没有控制台，这是唯一排查手段。
 *
 * 榜单来源分工：weibo / baidu 走自研抓取（WeiboHotFetcher / BaiduHotFetcher，
 * 聚合 API 这两个榜实测不可用），其余走聚合 API。
 * 榜单列表来自 SourceManager.newsTypes()（启用过滤 + 排序）。
 *
 * 线程模型：内部自持固定 6 线程池，回调发生在调用方线程
 * （本项目由 Tasks.io 驱动，回 UI 由调用方自行包 Tasks.ui）。
 * 用完后 pool 会 shutdown，不会常驻线程。
 */
public final class HotApiClient {

    /** 每批并发拉取的榜单数量 */
    public static final int BATCH_SIZE = 6;
    /** 单榜单超时（毫秒） */
    public static final int BOARD_TIMEOUT_MS = 10_000;
    /** 总预算（毫秒）：超时就返回已拿到的部分 */
    public static final int TOTAL_BUDGET_MS = 30_000;

    /** 渐进回调；snapshot 是当前进度的 boards 快照，顺序恒定 */
    public interface Callback {
        /** 每凑满一批（或全部结束）回调一次 */
        void onBatch(List<HotBoard> snapshot);

        /** 全部结束（或总预算用尽）回调一次，必达 */
        void onFinish(List<HotBoard> snapshot);
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(BATCH_SIZE);

    /**
     * 拉取全部已启用榜单。
     *
     * 榜单列表来源为 SourceManager.newsTypes()（启用过滤 + 排序），
     * 不再直接用 NewsRegistry.types()。
     */
    public void fetch(Callback callback) {
        List<HotBoard> boards = new ArrayList<>();
        for (String type : SourceManager.newsTypes()) {
            boards.add(new HotBoard(type, NewsRegistry.labelOf(type)));
        }
        fetch(boards, callback);
    }

    /**
     * 拉取给定榜单（按传入顺序）。
     *
     * @param boards   占位 HotBoard 列表，会被原地填充
     * @param callback 渐进回调，可为 null
     */
    public void fetch(List<HotBoard> boards, Callback callback) {
        if (boards == null || boards.isEmpty()) {
            if (callback != null) {
                callback.onFinish(new ArrayList<>());
            }
            return;
        }

        final int n = boards.size();
        ExecutorCompletionService<HotBoard> ecs = new ExecutorCompletionService<>(pool);
        List<Future<HotBoard>> futures = new ArrayList<>(n);
        long[] deadline = new long[n];      // 每个榜单各自的截止时间 = 提交时刻 + 单榜超时
        boolean[] collected = new boolean[n]; // 已取到结果或已判超时的榜单
        final long totalDeadline = System.currentTimeMillis() + TOTAL_BUDGET_MS;

        for (int i = 0; i < n; i++) {
            final HotBoard board = boards.get(i);
            deadline[i] = System.currentTimeMillis() + BOARD_TIMEOUT_MS;
            futures.add(ecs.submit(() -> fetchBoard(board)));
        }

        int completed = 0;
        int rendered = 0;
        while (completed < n) {
            long now = System.currentTimeMillis();
            if (now >= totalDeadline) {
                break; // 总预算用尽
            }
            // 本次等待时长 = min(总预算剩余, 最早的未完成榜单的剩余时间)
            long wait = totalDeadline - now;
            for (int i = 0; i < n; i++) {
                if (!collected[i] && !futures.get(i).isDone()) {
                    wait = Math.min(wait, Math.max(0L, deadline[i] - now));
                }
            }
            if (wait <= 0) {
                wait = 1;
            }

            Future<HotBoard> f;
            try {
                f = ecs.poll(wait, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (f != null) {
                // 该榜单已完成：Future#get(remainMs) 拿走结果。
                // 正常路径立即返回；极端竞态下 remainMs 仍兜底单榜超时。
                final int idx = futures.indexOf(f);
                if (idx < 0) {
                    continue;
                }
                long remain = Math.max(1L, deadline[idx] - System.currentTimeMillis());
                try {
                    f.get(remain, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    boards.get(idx).failReason = "单榜超时（10s）";
                    f.cancel(true);
                } catch (ExecutionException e) {
                    // fetchBoard 内部已捕获并写 failReason，这里防御性兜底
                    if (boards.get(idx).failReason == null || boards.get(idx).failReason.isEmpty()) {
                        boards.get(idx).failReason = describe(e.getCause());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (!collected[idx]) {
                    collected[idx] = true;
                    completed++;
                }
            } else {
                // poll 超时：必有榜单到达单榜截止时间，逐个标记，别把好榜拖下水
                now = System.currentTimeMillis();
                for (int i = 0; i < n; i++) {
                    if (!collected[i] && !futures.get(i).isDone() && now >= deadline[i]) {
                        futures.get(i).cancel(true);
                        boards.get(i).failReason = "单榜超时（10s）";
                        collected[i] = true;
                        completed++;
                    }
                }
            }

            // 渐进渲染：每凑满一批（或全部结束）回调一次
            if (callback != null && (completed - rendered >= BATCH_SIZE || completed == n)) {
                rendered = completed;
                callback.onBatch(new ArrayList<>(boards));
            }
        }

        // 总预算用尽：先把"已完成但还没从队列取走"的好结果落下来，再给剩下的判超时
        Future<HotBoard> done = ecs.poll();
        while (done != null) {
            try {
                done.get();
            } catch (Exception ignored) {
                // fetchBoard 内部已记录 failReason
            }
            int idx = futures.indexOf(done);
            if (idx >= 0 && !collected[idx]) {
                collected[idx] = true;
            }
            done = ecs.poll();
        }
        for (int i = 0; i < n; i++) {
            if (!collected[i]) {
                futures.get(i).cancel(true);
                if (boards.get(i).failReason == null || boards.get(i).failReason.isEmpty()) {
                    boards.get(i).failReason = "总预算超时（30s）";
                }
                collected[i] = true;
            }
        }

        pool.shutdown();
        if (callback != null) {
            callback.onFinish(new ArrayList<>(boards));
        }
    }

    /**
     * 拉单个榜单；任何异常都翻译成 failReason，绝不向上抛。
     *
     * weibo / baidu 走自研抓取（WeiboHotFetcher / BaiduHotFetcher）：
     * 聚合 API 的这两个榜实测不可用（weibo 返回 HTTP 500 错误页；
     * baidu 只回 1 条 title 为 undefined 的空记录）。
     * 其余 type 仍走 SourceKit.fetchJson 聚合 API。
     */
    private HotBoard fetchBoard(HotBoard board) {
        try {
            if ("weibo".equals(board.type) || "baidu".equals(board.type)) {
                boolean weibo = "weibo".equals(board.type);
                List<HotItem> items = weibo ? WeiboHotFetcher.fetch() : BaiduHotFetcher.fetch();
                board.title = weibo ? "微博热搜" : "百度热搜";
                board.total = items.size();
                board.data = items;
                board.updateTime = nowIso();
                return board;
            }
            String url = NewsRegistry.BASE_URL + board.type + "?cache=true";
            JSONObject json = SourceKit.fetchJson(url, null);
            JSONArray data = json.optJSONArray("data");
            if (data == null) {
                board.failReason = "响应结构不含 data";
                return board;
            }
            board.title = json.optString("title");
            board.updateTime = json.optString("updateTime");
            board.total = json.optInt("total", data.length());
            List<HotItem> items = new ArrayList<>(data.length());
            for (int i = 0; i < data.length(); i++) {
                JSONObject o = data.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                items.add(HotItem.fromJson(o));
            }
            board.data = items;
            int code = json.optInt("code", 200);
            if (code != 200) {
                board.failReason = "上游 code=" + code;
            }
        } catch (Exception e) {
            board.failReason = describe(e);
        }
        return board;
    }

    /** ISO 8601 UTC 时间串（禁 java.time：minSdk 23） */
    private static String nowIso() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }

    /** 把异常翻译成老设备上能看懂的原因：HTTP 码 / 非 JSON / 网络错误 */
    private static String describe(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
    }
}
