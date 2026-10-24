package com.wuji.reader.news;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个热榜：类型、中文标签、上游元信息、条目列表与失败原因。
 *
 * failReason 承载可诊断的失败原因（HTTP 码 / 单榜超时 / 非 JSON / 结构不含 data），
 * 老设备没有控制台，这是唯一排查手段，UI 必须把它显示出来。
 */
public class HotBoard {

    /** 上游 type，如 zhihu */
    public String type;
    /** 中文标签，如 知乎 */
    public String label;
    /** 上游返回的标题 */
    public String title = "";
    /** 上游返回的更新时间 */
    public String updateTime = "";
    /** 上游声明的条目数 */
    public int total;
    /** 条目列表 */
    public List<HotItem> data = new ArrayList<>();
    /** 失败原因；为空表示成功 */
    public String failReason = "";

    public HotBoard() {
    }

    public HotBoard(String type, String label) {
        this.type = type;
        this.label = label;
    }

    public boolean isFailed() {
        return failReason != null && !failReason.isEmpty();
    }

    /**
     * 是否真的有可看的内容：至少一条 title 非空。
     * 百度榜就栽在这里——返回 1 条 title 为 undefined 的空记录，
     * 不查这一条就会把用户默认带到"有页签、列表全白"的坏榜上。
     */
    public boolean hasContent() {
        if (data == null) {
            return false;
        }
        for (HotItem it : data) {
            if (it != null && it.title != null && !it.title.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
