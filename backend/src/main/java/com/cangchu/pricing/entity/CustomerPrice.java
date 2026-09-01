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

    /**
     * HMAC-SHA256(rt_phone) 盲索引（V30；W8-B3 起为定价身份唯一键，明文列已下线）。
     * 唯一产生点 {@code PiiCrypto.phoneHmac}。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String rtPhoneHmac;

    /** W8-B1 增（V31）/B3（V33）：rt_phone 尾号 4 位摘要（列表打码展示 `****1234`，免解密）。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String rtPhoneLast4;

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
