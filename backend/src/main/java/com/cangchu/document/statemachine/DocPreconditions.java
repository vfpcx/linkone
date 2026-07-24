package com.cangchu.document.statemachine;

import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.tenant.service.WholesalerService;
import com.cangchu.tenant.vo.WholesalerVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 单据前置钩子（P3 12 §1.1：商户状态校验抽公共，08 §7.5 R14）。
 *
 * <p>「wholesaler 必须 ACTIVE」（50313）在代建入库 / 代建出库 / WA 手动出库提交三处复用，
 * 不再散落 if。R14 分界（12 §8.3）：新单拒绝；已进入 PENDING_ACCEPT/PRINTED 的单在
 * 商户下架后允许走完（print/register/withdraw 等存量单作业<b>不</b>调本钩子）。
 */
@Component
@RequiredArgsConstructor
public class DocPreconditions {

    private final WholesalerService wholesalerService;

    /**
     * 校验商户存在且 ACTIVE，否则拒新业务。
     *
     * @return 商户视图（含 tenantId/ownerUserId，供调用方复用免二查）
     * @throws BizException WHOLESALER_NOT_FOUND(50230) / WHOLESALER_NOT_ACTIVE(50313)
     */
    public WholesalerVo requireWholesalerActive(Long wholesalerId) {
        WholesalerVo w = wholesalerService.getById(wholesalerId);
        if (w == null) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }
        if (!"ACTIVE".equals(w.getStatus())) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_ACTIVE);
        }
        return w;
    }
}
