package com.cangchu.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cangchu.account.entity.LoginSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * 登录会话 Mapper
 */
@Mapper
public interface LoginSessionMapper extends BaseMapper<LoginSession> {
}
