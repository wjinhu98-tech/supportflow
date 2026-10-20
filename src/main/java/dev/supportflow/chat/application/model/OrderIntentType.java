package dev.supportflow.chat.application.model;

/** 模型仅能提出查询单笔订单的意图，不能决定身份或权限。 */
public enum OrderIntentType {
    ORDER_LOOKUP,
    OTHER
}
