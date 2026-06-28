package com.cangchu.tenant.service;

import com.cangchu.tenant.dto.WholesalerCreateDto;
import com.cangchu.tenant.dto.WholesalerUpdateDto;
import com.cangchu.tenant.vo.WholesalerVo;

import java.util.List;

/**
 * 批发商商户服务（phase-1 仅 TA 自营创建 + 改资料 + 列表 + WA 账号开通最小实现）。
 */
public interface WholesalerService {

    /**
     * TA 自营创建批发商商户。
     *
     * @param tenantId       目标租户（以登录态推导的可信租户为准）
     * @param dto            创建请求
     * @param operatorUserId 操作人（必须是该 tenant 的 TA）
     */
    WholesalerVo createSelfOperated(Long tenantId, WholesalerCreateDto dto, Long operatorUserId);

    /** 修改商户资料（intro / license），校验归属同租户。 */
    WholesalerVo updateProfile(Long wholesalerId, Long operatorUserId, WholesalerUpdateDto dto);

    /** 列出本租户商户。 */
    List<WholesalerVo> listByTenant(Long tenantId);
}
