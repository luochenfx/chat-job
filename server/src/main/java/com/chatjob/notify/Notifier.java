package com.chatjob.notify;

/**
 * 通知通道。抽成接口的唯一理由是"以后换通道"不用改业务代码 ——
 * 企微机器人、Server酱、系统托盘都能挂上来。
 */
public interface Notifier {

    /**
     * @param kind 事件类型，用于节流合并（同一 kind 在一个窗口内只发一条）
     * @param title 标题（企微 markdown 里加粗）
     * @param body  正文，支持 markdown
     */
    void notify(String kind, String title, String body);
}
