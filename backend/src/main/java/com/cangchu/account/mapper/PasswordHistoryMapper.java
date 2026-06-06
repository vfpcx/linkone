package com.cangchu.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cangchu.account.entity.PasswordHistory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 密码历史 Mapper
 */
@Mapper
public interface PasswordHistoryMapper extends BaseMapper<PasswordHistory> {
}
