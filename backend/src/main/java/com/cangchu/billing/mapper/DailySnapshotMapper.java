package com.cangchu.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cangchu.billing.entity.DailySnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * 每日计费快照 Mapper（P4 W2）
 */
@Mapper
public interface DailySnapshotMapper extends BaseMapper<DailySnapshot> {
}
