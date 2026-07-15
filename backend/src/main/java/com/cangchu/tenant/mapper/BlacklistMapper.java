package com.cangchu.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cangchu.tenant.entity.Blacklist;
import org.apache.ibatis.annotations.Mapper;

/**
 * 平台黑名单 Mapper（P2 Wave1，平台级——不做租户隔离，O-6）。
 */
@Mapper
public interface BlacklistMapper extends BaseMapper<Blacklist> {
}
