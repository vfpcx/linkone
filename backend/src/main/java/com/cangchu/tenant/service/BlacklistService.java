package com.cangchu.tenant.service;

import com.cangchu.tenant.dto.BlacklistAddDto;
import com.cangchu.tenant.entity.Blacklist;

import java.util.List;

/**
 * 平台黑名单服务（P2 Wave1，OPS 专属管理；isBlacklisted 供入驻申请/OPS 代建复用）。
 */
public interface BlacklistService {

    /** OPS 黑名单列表（可按 status 过滤：ACTIVE/REMOVED；空=全部）。 */
    List<Blacklist> list(Long opsUserId, String status);

    /** OPS 加黑（手机号/执照号双键；重复 ACTIVE 条目拒绝 50310；REMOVED 条目复活）。 */
    Blacklist add(Long opsUserId, BlacklistAddDto dto);

    /** OPS 解除黑名单（status=REMOVED + removed_at，保留追溯；不存在/已解除 → 50311）。 */
    void remove(Long opsUserId, Long entryId);

    /**
     * 命中检查（全平台，入驻申请与 OPS 代建共用，决策 O-2）。
     * phone / license 任一为空则跳过对应键；均空返回 false。
     */
    boolean isBlacklisted(String phone, String license);
}
