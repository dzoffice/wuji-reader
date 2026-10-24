package com.wuji.reader.source;

import com.wuji.reader.model.Chapter;
import com.wuji.reader.model.Item;
import com.wuji.reader.model.Result;

import java.util.List;

/**
 * 漫画源契约（对应 JS 里的 class Xxx extends ComicExtension）。
 */
public interface ComicSource {

    String getId();

    String getName();

    /** 分类标签，如 国漫 / 日本 / 韩国 / 欧美 */
    List<String> types();

    Result<Item> getRecommendComics(int pageNo, String type) throws Exception;

    Result<Item> search(String keyword, int pageNo) throws Exception;

    /** 回填 item.desc 与 item.chapters（每话一个 Chapter） */
    Item getComicDetail(Item item, int pageNo) throws Exception;

    /** 返回该话的图片地址列表 */
    List<String> getContent(Item item, Chapter chapter) throws Exception;

    /**
     * 图片防盗链要带的 Referer；返回 null 表示不需要。
     * 默认 null，已有源行为不变。
     */
    default String imageReferer() {
        return null;
    }
}
