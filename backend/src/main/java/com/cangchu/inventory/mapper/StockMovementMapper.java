package com.cangchu.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cangchu.inventory.entity.StockMovement;
import org.apache.ibatis.annotations.Mapper;

/**
 * 库存流水 Mapper
 */
@Mapper
public interface StockMovementMapper extends BaseMapper<StockMovement> {
}
