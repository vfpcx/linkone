package com.cangchu.notify.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.notify.dto.AnnouncementCreateDto;
import com.cangchu.notify.vo.AnnouncementVo;

/**
 * 平台公告服务（P5-A W3，18-p5-design §4.2）。
 * OPS 管理：创建/列表/详情/发布/下架；发布同事务批量写目标角色站内信。
 */
public interface AnnouncementService {

    /** 创建公告草稿（OPS，返回公告 id） */
    Long create(Long operatorId, AnnouncementCreateDto dto);

    /** 公告列表（OPS，可按 status 过滤） */
    Page<AnnouncementVo> page(Long operatorId, int page, int size, String status);

    /** 公告详情（OPS） */
    AnnouncementVo detail(Long operatorId, Long id);

    /** 发布：DRAFT→PUBLISHED + 同事务批量写目标角色站内信（重复发布/非法迁移 → 50502） */
    void publish(Long operatorId, Long id);

    /** 下架：PUBLISHED→INACTIVE（已发站内信保留） */
    void inactivate(Long operatorId, Long id);

    /** 公告草稿计数（P5-C OPS 控制台「草稿待发布」待办，21 §3；requireOps 42002）。 */
    long countDrafts(Long opsUserId);
}
