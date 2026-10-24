package com.wuji.reader.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一的内容条目（小说 / 漫画共用）。
 * 对应 JS 源里 getRecommendBooks / search 返回的 list 元素。
 */
public class Item {

    /** 源内唯一 id（站点自身的 id；漫画详情页用 https://…/comic/{id} 拼详情） */
    public String id;
    public String title;
    public String author;
    public String cover;
    public String desc;
    /** 详情页地址，可选 */
    public String url;

    /** 详情接口回填：章节 / 话列表 */
    public List<Chapter> chapters;

    /** 所属源 id，便于跨链路传递 */
    public String sourceId;

    /** 来源源名，全局搜索时标注来源（普通模式为空） */
    public String sourceName;

    public Item() {
    }

    public Item(String id, String title) {
        this.id = id;
        this.title = title;
    }

    public List<Chapter> chaptersOrEmpty() {
        if (chapters == null) {
            chapters = new ArrayList<>();
        }
        return chapters;
    }

    public boolean hasChapters() {
        return chapters != null && !chapters.isEmpty();
    }
}
