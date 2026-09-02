package com.cangchu.document.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * wa 客户列表行（C3 · 24-p5-c-c3 §4.1）：客户 = 本商户按 rt_phone_hmac 归并的询价买家。
 *
 * <p>仅回打码号 maskedPhone（密文解密后 SmsUtil.maskPhone；解密失败回落 *** 不 500，PII-W7 先例）；
 * 查全号复用 PII-REVEAL biz=INQUIRY &amp; lastInquiryId（无明文落出参/日志）。
 */
@Data
public class CustomerListItemVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    private String wholesalerName;

    /** URL-safe Base64(rt_phone_hmac)（K-2）：detail/备注/提醒端点的操作键 */
    private String customerKey;

    private String maskedPhone;

    private Long inquiryCount;

    private LocalDateTime lastInquiryAt;

    /** 最近成交（CONFIRMED 单）时刻；无则 null */
    private LocalDateTime lastConfirmedAt;

    /** 最新询价单 id（查全号锚点：reveal biz=INQUIRY） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long lastInquiryId;

    /** 档案备注（无档案 null） */
    private String remark;

    private LocalDateTime remarkUpdatedAt;

    /** 最近未触发提醒时点（min remind_at where reminded_at IS NULL；无 null） */
    private LocalDateTime nextReminderAt;

    /** 已到点未触发条数（≤now 且未触发；Job 触发后归零） */
    private int dueReminderCount;
}
