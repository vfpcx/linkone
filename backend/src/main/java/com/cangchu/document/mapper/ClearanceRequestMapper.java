package com.cangchu.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cangchu.document.entity.ClearanceRequest;
import org.apache.ibatis.annotations.Mapper;

/**
 * 清库单 Mapper（P3b T4-W2）。clearance_requests 已纳入 TenantLine 白名单（兜底行级隔离）。
 */
@Mapper
public interface ClearanceRequestMapper extends BaseMapper<ClearanceRequest> {
}
