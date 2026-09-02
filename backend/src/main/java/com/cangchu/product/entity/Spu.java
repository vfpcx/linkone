package com.cangchu.product.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台标品 SPU（P5-D D56，22-p5-d56-catalog-design §2；归属 product 域）。
 *
 * <p>平台级表（无 tenant_id 列）：不在 TenantLine 白名单，天然不做租户隔离，OPS 管辖
 * （announcements 先例 V35）。无 @TableLogic：状态机已表达终态（MERGED/OFFLINE），不提供删除接口。
 *
 * <p>状态机：ACTIVE → OFFLINE（下架）/ ACTIVE → MERGED（合并源，事务内 skus 重指）；OFFLINE/MERGED 均不可再操作。
 */
@Data
@TableName("spus")
public class Spu {

    // ==================== 状态常量（状态机，22 §3.3） ====================
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_OFFLINE = "OFFLINE";
    public static final String STATUS_MERGED = "MERGED";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 平台编码（OPS 填 / 自动 GSPU-xxx；全局唯一） */
    private String spuCode;

    /** 标品名称（≤128） */
    private String name;

    /** 一级品类（预置字典中文文本） */
    private String categoryL1;

    /** 二级品类 */
    private String categoryL2;

    /** 品牌（自由文本，可空） */
    private String brand;

    /** 标准图（可空） */
    private String standardImageUrl;

    /** 备注（可空） */
    private String note;

    /** ACTIVE / OFFLINE / MERGED */
    private String status;

    /** 合并源指向的新主标品（仅 MERGED 非空） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long mergedToSpuId;

    /** 创建人（OPS） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
