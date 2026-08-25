package com.cangchu.pricing.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 客户专属价（P2 定价 Wave 1）。
 *
 * <p>身份口径：客户 = rt_phone（与 inquiry_requests.rt_phone 一致）。
 * 唯一键 (wholesaler_id, rt_phone, sku_id)。tenant_id 由 MetaObjectHandler 自动填充。
 * 状态 ACTIVE/DISABLED/EXPIRED；来源 manual/from_inquiry（Wave 1 仅 manual）。
 */
@Data
@TableName("customer_prices")
public class CustomerPrice {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_FROM_INQUIRY = "from_inquiry";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    /** 客户身份=RT手机号 */
    private String rtPhone;

    /**
     * HMAC-SHA256(rt_phone) 盲索引（V30，PII 阶段 0）。
     * 唯一产生点 {@code PiiCrypto.phoneHmac}；write-mode=dual 时随 rt_phone 同写，读路径不用。
     * JsonIgnore 保证任何实体直出的响应形状零变化（同 users.phone_hmac 口径）。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String rtPhoneHmac;

    /** 专属单价（>0） */
    private BigDecimal unitPrice;

    /** 状态 ACTIVE/DISABLED/EXPIRED */
    private String status;

    /** 来源 manual/from_inquiry */
    private String source;

    /** 来源单据号（from_inquiry 时填） */
    private String sourceDocNo;

    /** 失效时间（空=永久） */
    private LocalDateTime expireAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private Long createdBy;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;

    /** 有效判定：status=ACTIVE 且（无失效时间 或 失效时间在未来）。 */
    public boolean isActive() {
        return STATUS_ACTIVE.equals(status)
                && (expireAt == null || expireAt.isAfter(LocalDateTime.now()));
    }
}
