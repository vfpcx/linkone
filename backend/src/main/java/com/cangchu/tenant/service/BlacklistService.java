package com.cangchu.tenant.service;

import com.cangchu.tenant.dto.BlacklistAddDto;
import com.cangchu.tenant.entity.Blacklist;

import java.util.Map;

/**
 * 平台黑名单服务（P2 Wave1，OPS 专属管理；isBlacklisted 供入驻申请/OPS 代建复用）。
 */
public interface BlacklistService {

    /**
     * OPS 黑名单分页列表（Wave6 DEF-6：全量返回改分页，返回结构对齐
     * wholesaler-applications 的 PageRecords 契约 {records,total,page,size}）。
     *
     * @param status  可选过滤：ACTIVE/REMOVED；空=全部
     * @param keyword 可选键值搜索：匹配 target_value（手机号/执照号，模糊包含）
     */
    Map<String, Object> page(Long opsUserId, int page, int size, String status, String keyword);

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
