package com.cangchu.pricing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cangchu.pricing.entity.PriceChangeLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 价格变更日志 Mapper（P2 定价 Wave 2/3；Wave 1 仅建表）。
 */
@Mapper
public interface PriceChangeLogMapper extends BaseMapper<PriceChangeLog> {
}
