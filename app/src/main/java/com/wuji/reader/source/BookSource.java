package com.wuji.reader.source;

import com.wuji.reader.model.Chapter;
import com.wuji.reader.model.Item;
import com.wuji.reader.model.Result;

import java.util.List;

/**
 * 小说源契约（对应 JS 里的 class Xxx extends BookExtension）。
 *
 * 宿主侧要实现的 4 个方法：
 *   getRecommendBooks / search / getBookDetail / getContent
 */
public interface BookSource {

    String getId();

    String getName();

    /** 分类标签；返回空列表表示没有分类，直接给单一列表 */
    List<String> types();

    Result<Item> getRecommendBooks(int pageNo, String type) throws Exception;

    Result<Item> search(String keyword, int pageNo) throws Exception;

    /** 回填 item.desc 与 item.chapters */
    Item getBookDetail(Item item, int pageNo) throws Exception;

    /** 返回该章正文纯文本 */
    String getContent(Item item, Chapter chapter) throws Exception;
}
