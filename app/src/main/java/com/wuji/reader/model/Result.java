package com.wuji.reader.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 列表结果，对应 JS 源返回的 { list, page, totalPage }。
 */
public class Result<T> {

    public List<T> list = new ArrayList<>();
    public int page = 1;
    public int totalPage = 1;

    public Result() {
    }

    public Result(List<T> list, int page, int totalPage) {
        this.list = list == null ? new ArrayList<>() : list;
        this.page = page;
        this.totalPage = totalPage;
    }

    public boolean hasMore() {
        return page < totalPage;
    }
}
