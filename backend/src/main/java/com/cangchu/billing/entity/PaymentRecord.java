package com.cangchu.billing.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回款记录（P4 W3，14 §3.3/§4 V26；US-ST-04）。
 *
 * <p>平台不接资金（D03）：线下转账后 ST 手工登记。多次部分回款一次一条；
 * R12 冲销置 REVERSED 留痕不删（reverse_* 三列），paid_amount 回减 + 账单状态回退。
 */
@Data
@TableName("payment_records")
public class PaymentRecord {

    /** 有效（默认） */
    public static final String STATUS_EFFECTIVE = "EFFECTIVE";
    /** 已冲销（R12 留痕保留可见） */
    public static final String STATUS_REVERSED = "REVERSED";

    // 收款方式五枚举（14 §3.3）
    public static final String METHOD_BANK_TRANSFER = "BANK_TRANSFER";
    public static final String METHOD_CASH = "CASH";
    public static final String METHOD_WX = "WX";
    public static final String METHOD_ALIPAY = "ALIPAY";
    public static final String METHOD_OTHER = "OTHER";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long billId;

    /** 本次收款金额（>0 且 ≤剩余应收，50373 不允许超收） */
    private BigDecimal amount;

    /** 实付时间（ST 手填，可过去日期） */
    private LocalDateTime payAt;

    /** BANK_TRANSFER/CASH/WX/ALIPAY/OTHER */
    private String payMethod;

    /** 转账凭证 ≤5 张（/files 白名单 50340，JSON 数组文本） */
    private String evidenceUrls;

    private String remark;

    /** EFFECTIVE/REVERSED */
    private String status;

    /** R12 冲销理由（必填留痕） */
    private String reverseReason;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long reverseUserId;

    private LocalDateTime reverseAt;

    /** 登记 ST */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
