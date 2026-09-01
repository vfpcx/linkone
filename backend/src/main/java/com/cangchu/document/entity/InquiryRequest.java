package com.cangchu.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 询价单（phase-1 C2：RT提交询价 → WA确认 → 自动转出库）。
 */
@Data
@TableName("inquiry_requests")
public class InquiryRequest {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    /** R8 已作废（P3 BE-W2，12 §3.2：作废联动整单撤销+回补） */
    public static final String STATUS_VOIDED = "VOIDED";
    public static final String STATUS_COMPLETED = "COMPLETED";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String docNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    private String status;

    /**
     * HMAC-SHA256(rt_phone) 盲索引（V30；W8-B3 起为 RT 手机号身份键，明文列已下线）。
     * 唯一产生点 {@code PiiCrypto.phoneHmac}。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String rtPhoneHmac;

    /** W8-B1（V31）：AES-GCM(rt_phone) 密文影子列。W8-B3 起为 RT 手机号唯一载体（明文列已下线）。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String rtPhoneCipher;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private LocalDateTime confirmedAt;

    /** R8 作废时刻（V18，状态 VOIDED） */
    private LocalDateTime voidedAt;
}
