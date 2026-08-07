package com.cangchu.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cangchu.billing.entity.Bill;
import org.apache.ibatis.annotations.Mapper;

/**
 * 月度账单 Mapper（P4 W3）
 */
@Mapper
public interface BillMapper extends BaseMapper<Bill> {
}
