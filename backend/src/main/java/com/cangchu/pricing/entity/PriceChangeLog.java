package com.cangchu.pricing.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 价格变更日志（P2 定价 Wave 2/3）。
 *
 * <p>Wave 1 仅建表 + 实体/Mapper，暂不写入；批量调价沉淀在 Wave 2/3 落地。
 * 无软删；created_at 由 MetaObjectHandler 填充。
 */
@Data
@TableName("price_change_logs")
public class PriceChangeLog {

    public static final String CHANGE_TYPE_PUBLIC_PRICE = "PUBLIC_PRICE";
    public static final String CHANGE_TYPE_CUSTOMER_PRICE = "CUSTOMER_PRICE";

    public static final String ADJUST_MODE_PCT_UP = "PCT_UP";
    public static final String ADJUST_MODE_PCT_DOWN = "PCT_DOWN";
    public static final String ADJUST_MODE_SET_VALUE = "SET_VALUE";
    public static final String ADJUST_MODE_DELTA = "DELTA";
    public static final String ADJUST_MODE_DISABLE = "DISABLE";
    public static final String ADJUST_MODE_SET_EXPIRE = "SET_EXPIRE";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    private String batchNo;

    /** 变更类型 PUBLIC_PRICE/CUSTOMER_PRICE */
    private String changeType;

    /** 调整方式 PCT_UP/PCT_DOWN/SET_VALUE/DELTA/DISABLE/SET_EXPIRE */
    private String adjustMode;

    private Integer affectedCount;

    private String targetSummary;

    private String beforeAfterJson;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorUserId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
