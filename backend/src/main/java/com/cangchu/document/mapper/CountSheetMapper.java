package com.cangchu.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cangchu.document.entity.CountSheet;
import org.apache.ibatis.annotations.Mapper;

/** 盘点单 Mapper（P3b T3-W2；count_sheets 已纳入 TenantLine 白名单）。 */
@Mapper
public interface CountSheetMapper extends BaseMapper<CountSheet> {
}
