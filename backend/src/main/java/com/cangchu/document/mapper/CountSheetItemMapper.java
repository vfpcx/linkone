package com.cangchu.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cangchu.document.entity.CountSheetItem;
import org.apache.ibatis.annotations.Mapper;

/** 盘点单明细 Mapper（P3b T3-W2；count_sheet_items 已纳入 TenantLine 白名单）。 */
@Mapper
public interface CountSheetItemMapper extends BaseMapper<CountSheetItem> {
}
