package com.cangchu.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cangchu.inventory.entity.Inventory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 库存 Mapper
 */
@Mapper
public interface InventoryMapper extends BaseMapper<Inventory> {
}
