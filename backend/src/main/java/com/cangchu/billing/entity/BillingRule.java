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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 计费规则版本链（P4 W1，14-p4-design §2.1/§4 V24；D-P4-3=A 最小模型）。
 *
 * <p>租户级单一有效规则 + effective_from 版本化：effective_to IS NULL 即当前生效行；
 * 变更走「关旧行(to=新 from−1) + 插新行」，同日多次变更覆写当日行（uk_rule_tenant_from 一日一版）。
 * 版本链只追加不删（无 deleted_at），历史留痕供账单分段（R20）与审计。
 * wholesaler_id/min_charge/extra_rule_json 留位不启用（P4 恒 NULL/0，P5 收口）。
 */
@Data
@TableName("billing_rules")
public class BillingRule {

    /** 计费维度镜像值：仅件·天（tenant_settings.billing_dim 三值之一） */
    public static final String DIM_QTY = "QTY";
    /** 计费维度镜像值：仅托盘·天 */
    public static final String DIM_PALLET = "PALLET";
    /** 计费维度镜像值：两维并存（P4 新枚举，14 §2.1-3；读侧 storefront/TenantDetailVo 零改动） */
    public static final String DIM_BOTH = "BOTH";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    /** 留位恒 NULL（P5 per-WA 个性价，D-P4-3） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    /** 件·天维度启用（1/0；与托盘·天可并存，至少其一=1，应用层 50379） */
    private Integer qtyEnabled;

    /** 托盘·天维度启用（1/0） */
    private Integer palletEnabled;

    /** 件·天单价（qty_enabled=1 时必填 ≥0；停用维置 NULL） */
    private BigDecimal pricePerQtyDay;

    /** 托盘·天单价（pallet_enabled=1 时必填 ≥0；停用维置 NULL） */
    private BigDecimal pricePerPalletDay;

    /** 留位不启用（P5 保底费，P4 恒 0） */
    private BigDecimal minCharge;

    /** 留位不启用（P5 阶梯/临期加价，P4 恒 NULL） */
    private String extraRuleJson;

    /** 生效日（首版=首次保存日 D-P4-4；变更日按新规则 05 §1.5） */
    private LocalDate effectiveFrom;

    /** NULL=当前生效；关旧=新版 effective_from−1 */
    private LocalDate effectiveTo;

    /** 版本号自 1 递增（同日覆写不递增） */
    private Integer version;

    /** 保存人（仓库管理员） */
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
