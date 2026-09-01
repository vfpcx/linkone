package com.cangchu.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cangchu.tenant.entity.StorefrontFeature;
import org.apache.ibatis.annotations.Mapper;

/**
 * 店铺撮合配置 Mapper（P5-A W4；storefront_featured 已纳入 TenantLine 白名单）。
 * 仅 tenant 域读写；storefront 域只读消费走 {@link com.cangchu.tenant.service.StorefrontFeatureService}。
 */
@Mapper
public interface StorefrontFeatureMapper extends BaseMapper<StorefrontFeature> {
}
