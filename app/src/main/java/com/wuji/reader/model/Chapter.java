package com.wuji.reader.model;

/**
 * 章节（小说）或话（漫画）。
 * 对应 JS 源里 item.chapters 的每一项。
 */
public class Chapter {

    public String id;
    public String title;
    public String url;

    public Chapter() {
    }

    public Chapter(String id, String title) {
        this.id = id;
        this.title = title;
    }

    public Chapter(String id, String title, String url) {
        this.id = id;
        this.title = title;
        this.url = url;
    }
}
