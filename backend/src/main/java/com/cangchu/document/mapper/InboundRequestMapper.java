package com.cangchu.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cangchu.document.entity.InboundRequest;
import org.apache.ibatis.annotations.Mapper;

/**
 * 入库单 Mapper（phase-1 C1）。
 */
@Mapper
public interface InboundRequestMapper extends BaseMapper<InboundRequest> {
}
