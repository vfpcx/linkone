package com.cangchu.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.vo.RtTenantDirectoryItemVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {

    /**
     * RT 首页「附近仓库」公开目录（US-RT-06）。
     *
     * <p>仅返回 ACTIVE 租户及其默认店铺；不传坐标时按创建时间倒序、距离为 null。
     * 坐标采用 GCJ-02，距离按 haversine 公式估算（米）。
     *
     * <p>公开端点无 TenantContext，TenantLine 不注入租户条件；本查询 JOIN tenants/stores
     * 两张全局表，自然全平台可见。
     */
    @Select("""
            SELECT
                t.id AS tenantId,
                t.tenant_simple_code AS tenantSimpleCode,
                s.id AS storeId,
                s.name AS storeName,
                s.intro AS intro,
                s.lng AS lng,
                s.lat AS lat,
                CASE
                    WHEN s.lng IS NOT NULL AND s.lat IS NOT NULL AND #{lng} IS NOT NULL AND #{lat} IS NOT NULL THEN
                        ROUND(6371000 * 2 * ASIN(SQRT(
                            POWER(SIN((s.lat - #{lat}) * PI() / 360), 2) +
                            COS(#{lat} * PI() / 180) * COS(s.lat * PI() / 180) *
                            POWER(SIN((s.lng - #{lng}) * PI() / 360), 2)
                        )))
                    ELSE NULL
                END AS distanceMeters
            FROM tenants t
            JOIN stores s ON s.tenant_id = t.id AND s.deleted_at IS NULL
            WHERE t.status = 'ACTIVE' AND t.deleted_at IS NULL
            ORDER BY
                CASE
                    WHEN s.lng IS NOT NULL AND s.lat IS NOT NULL AND #{lng} IS NOT NULL AND #{lat} IS NOT NULL THEN 0
                    ELSE 1
                END,
                distanceMeters ASC,
                t.created_at DESC
            LIMIT #{limit}
            """)
    List<RtTenantDirectoryItemVo> selectNearbyStores(@Param("lat") BigDecimal lat,
                                                     @Param("lng") BigDecimal lng,
                                                     @Param("limit") int limit);
}
