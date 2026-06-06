package com.cangchu.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cangchu.account.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
