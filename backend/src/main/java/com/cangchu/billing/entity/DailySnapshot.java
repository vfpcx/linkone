package com.cangchu.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日计费快照（P4 W2，14 §1.2/§4 V25）。
 *
 * <p>快照 = 统一回放公式（14 §1.1）的逐日物化，仅作①按日下钻②对账留痕③缓存三用途，
 * <b>非记账真源</b>——出账以流水回放为准（D-P4-2=A）。只存物理量不存金额；双 0 不落行，
 * 缺行即 0。tenant_id 由写入方显式设置（Job 无 TenantContext 不能依赖自动填充）。
 */
@Data
@TableName("daily_snapshots")
public class DailySnapshot {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    /** 快照日 D（值为 bizDate ≤ D−1 的净值，14 §1.1） */
    private LocalDate snapshotDate;

    /** 计费在库件数 billableQty(D) = max(Σsigned, 0) */
    private Integer qty;

    /** 计费占用托盘 billablePallet(D) = max(Σpallet_delta, 0)；V20 前存量基线 0（D-P4-5=A） */
    private Integer palletQty;

    private LocalDateTime createdAt;
}
