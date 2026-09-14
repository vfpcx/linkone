package com.cangchu.tenant.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * RT 首页「附近仓库」公开目录项（US-RT-06 · 基于位置推荐仓库）。
 *
 * <p>仅返回已审核 ACTIVE 仓库的公开信息：id、店铺名、店铺码、坐标、距离。
 * 不携带联系人、营业执照、容量等敏感/非公开字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RtTenantDirectoryItemVo {

    /** 租户（仓库）id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    /** 店铺 id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    /** 店铺名 */
    private String storeName;

    /** 店铺码（= 租户简码 tenantSimpleCode，可复用进店） */
    private String tenantSimpleCode;

    /** 店铺简介 */
    private String intro;

    /** 经度 */
    private BigDecimal lng;

    /** 纬度 */
    private BigDecimal lat;

    /**
     * 与当前请求坐标的直线距离（米）。
     * 当仓库未设坐标或请求未传坐标时为 null。
     */
    private Integer distanceMeters;
}
