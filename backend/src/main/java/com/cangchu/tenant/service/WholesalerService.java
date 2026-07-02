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

    /**
     * 只读：按 id 取单个批发商（供 document 等编排域读取，替代跨域直连 WholesalerMapper，符合 G-S1/G-S2）。
     * 隔离行为等同于原 {@code wholesalerMapper.selectById}——内部同经 WholesalerMapper，受 TenantLine 兜底过滤，
     * 跨租户不可见时返回 {@code null}；调用方负责存在性判断与业务错误码。
     *
     * @return 命中的批发商视图；不存在（含被 TenantLine 过滤）返回 null
     */
    WholesalerVo getById(Long wholesalerId);
}
