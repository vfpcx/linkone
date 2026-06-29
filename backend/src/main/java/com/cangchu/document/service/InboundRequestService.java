package com.cangchu.document.service;

import com.cangchu.document.dto.InboundRegisterDto;
import com.cangchu.document.vo.InboundRequestVo;

import java.util.List;

/**
 * 入库单服务（phase-1 C1：WK 代建登记）。
 *
 * <p>phase-1 简化为「WK 直接登记入库」：单事务内 生成 docNo → 建 inbound_requests(REGISTERED)
 * → 调 {@code inventoryService.addStock} 增库存 + 写 INBOUND 流水。不做 72h 异议/仲裁/退货/盘点。
 */
public interface InboundRequestService {

    /**
     * WK 登记入库（单事务）。
     *
     * @param dto      入库登记参数（wholesalerId/skuId/qty/palletQty?）
     * @param wkUserId 操作人（WK）user id（来自登录态，由 Controller 传入）
     * @return 入库单 VO（含 docNo + 登记后最新库存）
     */
    InboundRequestVo registerByWk(InboundRegisterDto dto, Long wkUserId);

    /**
     * 列出本租户入库单（按创建时间倒序）。tenantId 由调用方从登录态推导后传入。
     *
     * @param tenantId     租户 id
     * @param wholesalerId 可空；非空则只列该商户的入库单
     */
    List<InboundRequestVo> listByTenant(Long tenantId, Long wholesalerId);
}
