package com.cangchu.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cangchu.billing.entity.BillItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 账单明细行 Mapper（P4 W3）
 */
@Mapper
public interface BillItemMapper extends BaseMapper<BillItem> {
}
