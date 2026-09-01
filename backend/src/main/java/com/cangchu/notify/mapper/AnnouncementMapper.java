package com.cangchu.notify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cangchu.notify.entity.Announcement;
import org.apache.ibatis.annotations.Mapper;

/**
 * 平台公告 Mapper（P5-A W3；平台级表，不做租户隔离，OPS 管辖）。
 */
@Mapper
public interface AnnouncementMapper extends BaseMapper<Announcement> {
}
