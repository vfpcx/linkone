package com.cangchu.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cangchu.document.entity.InboundCorrection;
import org.apache.ibatis.annotations.Mapper;

/**
 * R3 登记纠错单 Mapper（P3b T1-BE）。
 */
@Mapper
public interface InboundCorrectionMapper extends BaseMapper<InboundCorrection> {
}
