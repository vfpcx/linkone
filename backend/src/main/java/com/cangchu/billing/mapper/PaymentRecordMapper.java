package com.cangchu.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cangchu.billing.entity.PaymentRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 回款记录 Mapper（P4 W3）
 */
@Mapper
public interface PaymentRecordMapper extends BaseMapper<PaymentRecord> {
}
