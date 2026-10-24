package com.wuji.reader.ui;

/**
 * 九宫格分区可绑定的动作。
 *
 * 墨水屏没有悬停、没有右键，所有操作都靠"点哪块屏幕"来触发，
 * 所以动作集合要短、要够用：翻章、字号、目录、菜单、清屏是高频动作。
 *
 * ordinal 值会被写进 SharedPreferences，**只增不改不改顺序**，
 * 否则老设备上的配置会错位。
 */
public enum NineGridAction {

    NONE("无"),
    CLEAR_SCREEN("清屏"),
    MENU("阅读菜单"),
    PREV_CHAPTER("上一章"),
    NEXT_CHAPTER("下一章"),
    FONT_UP("字号+"),
    FONT_DOWN("字号-"),
    TOC("目录"),
    TOP("回顶部"),
    BOTTOM("跳到底部"),
    PROGRESS("阅读进度"),
    REFRESH("重新加载"),
    COPY("复制正文"),
    BACK("返回"),
    SETTINGS("阅读设置"),
    PREV_PAGE("上一页"),
    NEXT_PAGE("下一页"),
    AUTO_SCROLL("自动滚动"),
    ZOOM_IN("放大"),
    ZOOM_OUT("缩小"),
    RELATED("相关报道");

    public final String label;

    NineGridAction(String label) {
        this.label = label;
    }

    /** 越界或脏数据一律退回 NONE，绝不抛异常 */
    public static NineGridAction byId(int id) {
        NineGridAction[] all = values();
        return (id >= 0 && id < all.length) ? all[id] : NONE;
    }
}
