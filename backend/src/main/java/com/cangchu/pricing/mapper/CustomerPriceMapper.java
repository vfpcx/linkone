package com.cangchu.pricing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cangchu.pricing.entity.CustomerPrice;
import org.apache.ibatis.annotations.Mapper;

/**
 * 客户专属价 Mapper（P2 定价 Wave 1）。
 */
@Mapper
public interface CustomerPriceMapper extends BaseMapper<CustomerPrice> {
}
