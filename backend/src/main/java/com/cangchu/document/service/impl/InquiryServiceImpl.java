package com.cangchu.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SmsUtil;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.common.pii.PiiCrypto;
import com.cangchu.document.dto.ConfirmInquiryDto;
import com.cangchu.document.dto.SubmitInquiryDto;
import com.cangchu.document.entity.InquiryItem;
import com.cangchu.document.entity.InquiryRequest;
import com.cangchu.document.entity.OutboundRequest;
import com.cangchu.document.enums.DocType;
import com.cangchu.document.mapper.InquiryItemMapper;
import com.cangchu.document.mapper.InquiryRequestMapper;
import com.cangchu.document.mapper.OutboundRequestMapper;
import com.cangchu.document.service.DocumentNumberService;
import com.cangchu.document.service.InquiryService;
import com.cangchu.document.vo.InquiryVo;
import com.cangchu.inventory.dto.OutboundContext;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.pricing.service.PricingService;
import com.cangchu.product.service.SkuService;
import com.cangchu.product.vo.SkuVo;
import com.cangchu.storefront.service.StoreFrontService;
import com.cangchu.storefront.vo.StoreFrontVo;
import com.cangchu.storefront.vo.StoreWholesalerVo;
import com.cangchu.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 询价服务实现（phase-1 C2：RT 提交 → WA 确认 → 自动转出库扣库存）。
 *
 * <p>安全规约（05-secure-coding-guardrails）：
 * <ul>
 *   <li>G-2.1 租户隔离：submitByRt 无登录态，tenantId 由 store→tenant 解析（复用 B2 StoreFrontService），
 *       <b>不取客户端</b>；wholesaler/sku 经显式 tenantId 核对。</li>
 *   <li>S2 校验（G-3.1）：items 非空、qty>0、wholesaler 属店、sku 属 wholesaler。</li>
 *   <li>S4 越权（G-1.3）：confirmByWa 校验操作人在该 wholesaler 下有 ACTIVE 的 WA 角色（user_roles 唯一可信来源）。</li>
 *   <li>S5 状态机 + 单事务（G-4.1/G-5.1）：confirm 仅允许 PENDING；编排「建出库单 + deductStock 扣库存」在<b>单事务</b>内，
 *       任一 item 库存不足 deductStock 抛 STOCK_NOT_ENOUGH，整个确认事务回滚（inquiry 仍 PENDING、无 outbound、库存未扣）。</li>
 *   <li>S6 唯一（G-5.1）：docNo 由 DocumentNumberService 生成 + doc_no 唯一索引兜底。</li>
 * </ul>
 *
 * <p>事务说明：deductStock 自身在 Redisson 锁内经代理调 doDeductInTx(@Transactional)，传播 REQUIRED 并入
 * confirmByWa 的外层事务，库存扣减与单据写入同生共死。
 *
 * <p>inquiry_items 无 tenant_id 列（不在 TenantLine 白名单），其租户隔离经 inquiry_id 间接保证。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InquiryServiceImpl implements InquiryService {

    private final InquiryRequestMapper inquiryRequestMapper;
    private final InquiryItemMapper inquiryItemMapper;
    private final OutboundRequestMapper outboundRequestMapper;
    // G-S1/G-S2 还债：他域数据只走对方 Service（不再直连 SkuMapper/TenantMapper）
    private final SkuService skuService;
    private final TenantService tenantService;
    // P2 入驻 Wave2：R14 新拒老放分界需读商户状态（经 tenant 域 Service，不直连 WholesalerMapper）
    private final com.cangchu.tenant.service.WholesalerService wholesalerService;
    // G-S1/G-S2 还债：user_roles 归 account 域，requireWaRole/listForWa 经 AuthService 鉴权/查询。
    private final AuthService authService;
    private final StoreFrontService storeFrontService;
    private final DocumentNumberService documentNumberService;
    private final InventoryService inventoryService;
    // P2 定价 Wave 3a：议价沉淀经 PricingService（document 域不直连 CustomerPriceMapper）
    private final PricingService pricingService;
    // P3 BE-W2（12 §3.2）：R8 作废联动站内信
    private final com.cangchu.notify.service.NotificationService notificationService;
    private final SnowflakeIdUtil snowflakeIdUtil;
    // PII 阶段 0（V30）：rt_phone 盲索引双写的唯一产生点；读路径一律不用
    private final PiiCrypto piiCrypto;

    // ==================== RT 提交询价 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InquiryVo submitByRt(SubmitInquiryDto dto) {
        // S2：必填 + items
        if (dto.getWholesalerId() == null) {
            throw new BizException(ErrorCode.INQUIRY_WHOLESALER_NOT_IN_STORE, "缺少批发商");
        }
        if (dto.getItems() == null || dto.getItems().isEmpty()) {
            throw new BizException(ErrorCode.INQUIRY_ITEMS_REQUIRED);
        }
        for (SubmitInquiryDto.InquiryItemDto it : dto.getItems()) {
            if (it.getSkuId() == null) {
                throw new BizException(ErrorCode.VALIDATION_BASIC_003, "缺少商品 SKU");
            }
            if (it.getQty() == null || it.getQty() <= 0) {
                throw new BizException(ErrorCode.INQUIRY_QTY_INVALID);
            }
        }

        // 解析 store→tenant，并校验 wholesaler 属该店（仅 ACTIVE）。复用 B2 StoreFrontService（不取客户端 tenantId）。
        StoreFrontVo store = storeFrontService.getStorePage(dto.getStoreId(), dto.getCode());
        Long tenantId = store.getTenantId();
        Long storeId = store.getStoreId();

        // P2 Wave2 R14/R13（FOF 分界·新业务拒绝）：商户属本店但已 OFFLINE/WITHDRAWN → 明确拒 50313
        // （区别于"不属本店"的 50282，不泄漏跨店信息的前提是先核对租户归属）。
        com.cangchu.tenant.vo.WholesalerVo wsState = wholesalerService.getById(dto.getWholesalerId());
        if (wsState != null && tenantId.equals(wsState.getTenantId())
                && !"ACTIVE".equals(wsState.getStatus())) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_ACTIVE);
        }

        boolean waInStore = store.getWholesalers() != null && store.getWholesalers().stream()
                .map(StoreWholesalerVo::getWholesalerId)
                .anyMatch(id -> id.equals(dto.getWholesalerId()));
        if (!waInStore) {
            throw new BizException(ErrorCode.INQUIRY_WHOLESALER_NOT_IN_STORE);
        }

        // 建询价单（PENDING）
        String docNo = documentNumberService.generate(DocType.INQUIRY, resolveSimpleCode(tenantId));
        InquiryRequest req = new InquiryRequest();
        req.setId(snowflakeIdUtil.nextId());
        req.setDocNo(docNo);
        req.setStoreId(storeId);
        req.setTenantId(tenantId);
        req.setWholesalerId(dto.getWholesalerId());
        req.setStatus(InquiryRequest.STATUS_PENDING);
        req.setRtPhone(dto.getRtPhone());
        // PII 阶段 0（V30）：write-mode=dual 才写 hmac 列；读路径仍走 rt_phone 明文
        if (piiCrypto.isDualWrite()) {
            req.setRtPhoneHmac(piiCrypto.phoneHmac(dto.getRtPhone()));
        }
        try {
            inquiryRequestMapper.insert(req);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.DOC_NO_GENERATE_FAILED);
        }

        // 建明细（含公开价快照）。每个 sku 必须属该 wholesaler 且同租户（显式核对）。
        for (SubmitInquiryDto.InquiryItemDto it : dto.getItems()) {
            SkuVo sku = skuService.getById(it.getSkuId());
            if (sku == null
                    || !tenantId.equals(sku.getTenantId())
                    || !dto.getWholesalerId().equals(sku.getWholesalerId())) {
                throw new BizException(ErrorCode.INQUIRY_SKU_NOT_BELONG);
            }
            BigDecimal unitPrice = sku.getUnitPrice() != null ? sku.getUnitPrice() : BigDecimal.ZERO;
            InquiryItem item = new InquiryItem();
            item.setId(snowflakeIdUtil.nextId());
            item.setInquiryId(req.getId());
            item.setSkuId(it.getSkuId());
            item.setQty(it.getQty());
            item.setUnitPriceSnapshot(unitPrice);
            item.setMoqPriceSnapshot(sku.getMoqPrice() != null ? sku.getMoqPrice() : BigDecimal.ZERO);
            item.setMoqQtySnapshot(sku.getMoqQty() != null ? sku.getMoqQty() : 0);
            item.setDealPrice(unitPrice); // phase-1：成交价 = 单价快照
            inquiryItemMapper.insert(item);
        }

        // X硬化 H4：日志严禁明文手机号（F7 规约，统一走 SmsUtil.maskPhone）
        log.info("[C2] RT {} 提交询价 doc={} store={} wholesaler={} items={}",
                SmsUtil.maskPhone(dto.getRtPhone()), docNo, storeId, dto.getWholesalerId(), dto.getItems().size());

        return loadVo(req.getId());
    }

    // ==================== WA 确认 → 自动转出库 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InquiryVo confirmByWa(Long inquiryId, ConfirmInquiryDto dto, Long waUserId) {
        // P2 Wave 3a：dto 可空（无请求体沿用 phase-1 行为）
        if (dto == null) {
            dto = new ConfirmInquiryDto();
        }
        // 逐条议价映射：inquiryItemId → dealPrice（容错缺项/空值）
        Map<Long, BigDecimal> dealOverrides = new HashMap<>();
        if (dto.getItems() != null) {
            for (ConfirmInquiryDto.Item it : dto.getItems()) {
                if (it != null && it.getInquiryItemId() != null && it.getDealPrice() != null) {
                    dealOverrides.put(it.getInquiryItemId(), it.getDealPrice());
                }
            }
        }
        boolean settle = dto.isSettleAsCustomerPrice();

        if (inquiryId == null) {
            throw new BizException(ErrorCode.INQUIRY_NOT_FOUND);
        }
        InquiryRequest inquiry = inquiryRequestMapper.selectById(inquiryId);
        if (inquiry == null) {
            throw new BizException(ErrorCode.INQUIRY_NOT_FOUND);
        }

        // S4：操作人必须在该 inquiry 的 wholesaler 下有 ACTIVE 的 WA 角色（user_roles 唯一可信来源）
        requireWaRole(inquiry.getWholesalerId(), waUserId);

        // P2 Wave2 R14（FOF 分界·老业务放行/新业务拒绝）：分界 = 下架时刻的单据状态——
        // 未确认（PENDING）询价在商户 OFFLINE/WITHDRAWN 后**不可再确认**（此处拦 50313）；
        // 下架前已确认的询价在本实现中确认即原子转出库完成（CONFIRMED→COMPLETED 同事务），
        // 已生成的出库单/已完成单据不受影响（不做一刀切回滚）。
        com.cangchu.tenant.vo.WholesalerVo wsState = wholesalerService.getById(inquiry.getWholesalerId());
        if (wsState == null || !"ACTIVE".equals(wsState.getStatus())) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_ACTIVE);
        }

        // S5 + 并发（§10 P2 状态条件 CAS）：仅 PENDING 可确认；用 UPDATE...WHERE status=PENDING
        // 校验 affected==1，防止并发双击两个请求都读到 PENDING 而重复建出库单/重复扣库存。
        if (!InquiryRequest.STATUS_PENDING.equals(inquiry.getStatus())) {
            throw new BizException(ErrorCode.INQUIRY_STATUS_INVALID);
        }
        int cas = inquiryRequestMapper.update(null, new LambdaUpdateWrapper<InquiryRequest>()
                .set(InquiryRequest::getStatus, InquiryRequest.STATUS_CONFIRMED)
                .set(InquiryRequest::getConfirmedAt, java.time.LocalDateTime.now())
                .eq(InquiryRequest::getId, inquiry.getId())
                .eq(InquiryRequest::getStatus, InquiryRequest.STATUS_PENDING));
        if (cas != 1) {
            // 并发竞争失败或已被他人确认 → 拒绝，避免重复出库
            throw new BizException(ErrorCode.INQUIRY_STATUS_INVALID);
        }

        Long tenantId = inquiry.getTenantId();
        Long wholesalerId = inquiry.getWholesalerId();
        String simpleCode = resolveSimpleCode(tenantId);

        // 遍历 items → 每条生成出库单 + 扣库存（库存不足整体回滚）
        List<InquiryItem> items = inquiryItemMapper.selectList(new LambdaQueryWrapper<InquiryItem>()
                .eq(InquiryItem::getInquiryId, inquiry.getId()));
        for (InquiryItem item : items) {
            // P2 Wave 3a：逐条议价 → 覆盖并落库成交价（phase-1 默认成交价=公开价快照）
            BigDecimal dealPrice = item.getDealPrice();
            if (dealOverrides.containsKey(item.getId())) {
                BigDecimal override = dealOverrides.get(item.getId());
                if (override.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BizException(ErrorCode.CUSTOMER_PRICE_INVALID, "议定成交价必须大于0");
                }
                dealPrice = override;
                item.setDealPrice(override);
                inquiryItemMapper.update(null, new LambdaUpdateWrapper<InquiryItem>()
                        .eq(InquiryItem::getId, item.getId())
                        .set(InquiryItem::getDealPrice, override));
            }

            String outDocNo = documentNumberService.generate(DocType.OUTBOUND, simpleCode);
            OutboundRequest out = new OutboundRequest();
            out.setId(snowflakeIdUtil.nextId());
            out.setDocNo(outDocNo);
            out.setInquiryId(inquiry.getId());
            out.setTenantId(tenantId);
            out.setWholesalerId(wholesalerId);
            out.setSkuId(item.getSkuId());
            out.setQty(item.getQty());
            // P3 BE-W2（12 §1.4 唯一触 P1 主链的改动）：出库单起点 PENDING_ACCEPT（扣库存时点不动），
            // 后续 WK 打印→登记出库走 DocStateMachine；来源标记 INQUIRY_AUTO。
            out.setStatus(OutboundRequest.STATUS_PENDING_ACCEPT);
            out.setSource(OutboundRequest.SOURCE_INQUIRY_AUTO);
            out.setPrintCount(0);
            out.setWithdrawRequested(0);
            out.setWkUserId(waUserId);
            try {
                outboundRequestMapper.insert(out);
            } catch (DuplicateKeyException e) {
                throw new BizException(ErrorCode.OUTBOUND_GENERATE_FAILED);
            }

            // 扣库存：deductStock 在 Redisson 锁内单事务执行，库存不足抛 STOCK_NOT_ENOUGH → 整个确认事务回滚
            inventoryService.deductStock(OutboundContext.builder()
                    .wholesalerId(wholesalerId)
                    .tenantId(tenantId)
                    .skuId(item.getSkuId())
                    .qty(item.getQty())
                    .refDocNo(outDocNo)
                    .operatorUserId(waUserId)
                    .build());

            // P2 Wave 3a：议价沉淀。成交价≠提交时公开价快照才落客户专属价（同事务，回滚一并撤销）。
            if (settle
                    && inquiry.getRtPhone() != null && !inquiry.getRtPhone().isBlank()
                    && dealPrice != null
                    && (item.getUnitPriceSnapshot() == null
                        || dealPrice.compareTo(item.getUnitPriceSnapshot()) != 0)) {
                pricingService.settleFromInquiry(wholesalerId, inquiry.getRtPhone(),
                        item.getSkuId(), dealPrice, inquiry.getDocNo(), waUserId);
            }
        }

        // P3 BE-W2（12 §1.4）：确认后停在 CONFIRMED；该询价名下全部出库单登记出库（COMPLETED）时
        // 由出库链在登记同事务内联动迁 COMPLETED（OutboundRequestServiceImpl.recomputeInquiryState）。
        log.info("[C2] WA {} 确认询价 doc={} wholesaler={} 生成出库 {} 条（PENDING_ACCEPT）→ CONFIRMED",
                waUserId, inquiry.getDocNo(), wholesalerId, items.size());

        return loadVo(inquiry.getId());
    }

    // ==================== R8 作废联动（P3 BE-W2，12 §3.2） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InquiryVo voidByWa(Long inquiryId, Long waUserId) {
        if (inquiryId == null) {
            throw new BizException(ErrorCode.INQUIRY_NOT_FOUND);
        }
        InquiryRequest inquiry = inquiryRequestMapper.selectById(inquiryId);
        if (inquiry == null) {
            throw new BizException(ErrorCode.INQUIRY_NOT_FOUND);
        }
        requireWaRole(inquiry.getWholesalerId(), waUserId);

        // 前置：仅 CONFIRMED 可作废；名下存在已出库/客诉中单据 → 50337
        if (!InquiryRequest.STATUS_CONFIRMED.equals(inquiry.getStatus())) {
            throw new BizException(ErrorCode.INQUIRY_NOT_VOIDABLE, "意向单当前状态不可作废");
        }
        List<OutboundRequest> outbounds = outboundRequestMapper.selectList(
                new LambdaQueryWrapper<OutboundRequest>().eq(OutboundRequest::getInquiryId, inquiryId));
        boolean anyCompleted = outbounds.stream().anyMatch(o ->
                OutboundRequest.STATUS_COMPLETED.equals(o.getStatus())
                        || OutboundRequest.STATUS_COMPLAINED.equals(o.getStatus()));
        if (anyCompleted) {
            throw new BizException(ErrorCode.INQUIRY_NOT_VOIDABLE);
        }

        // N4（08-p3-review）：加锁次序统一为「出库行 → 询价行」——R4 撤回/登记出库是
        // 出库 CAS 后经 recomputeInquiryState 更新询价（outbound→inquiry），此处原先反向
        // （inquiry→outbound）与之构成 AB-BA 死锁窗口（InnoDB 检测后牺牲一方透出 90001 而非
        // 50331 语义码）。故先逐张撤销出库单，最后 CAS 询价；询价 CAS 失败整体回滚，
        // 出库撤销与回补一并回退，语义不变。
        // 名下 PENDING_ACCEPT / PRINTED 逐张 CAS→CANCELLED + 回补（每张一条 OUTBOUND_REVERSAL 配对，12 §3.2）。
        // 已打印单在 R8 下不需 WK 二次确认（作废是整单意思表示），但通知 WK 收回纸单。
        LocalDateTime now = LocalDateTime.now();
        int cancelled = 0;
        boolean anyPrinted = false;
        for (OutboundRequest out : outbounds) {
            String from = out.getStatus();
            if (!OutboundRequest.STATUS_PENDING_ACCEPT.equals(from)
                    && !OutboundRequest.STATUS_PRINTED.equals(from)) {
                continue; // WITHDRAWN/CANCELLED 已回补，跳过
            }
            anyPrinted |= OutboundRequest.STATUS_PRINTED.equals(from);
            boolean moved = com.cangchu.document.statemachine.DocStateMachine.casTransition(
                    outboundRequestMapper, com.cangchu.document.statemachine.DocStateMachine.DocKind.OUTBOUND,
                    out.getId(), OutboundRequest::getId, OutboundRequest::getStatus,
                    from, OutboundRequest.STATUS_CANCELLED, null);
            if (!moved) {
                // 并发被抢占（如同刻登记出库）→ 整单作废失败回滚，让用户刷新重试
                throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
            }
            inventoryService.reverseOutbound(com.cangchu.inventory.dto.OutboundReversalContext.builder()
                    .wholesalerId(out.getWholesalerId())
                    .tenantId(out.getTenantId())
                    .skuId(out.getSkuId())
                    .qty(out.getQty())
                    .refDocNo(out.getDocNo())
                    .operatorUserId(waUserId)
                    .remark("R8 意向单作废回补")
                    .build());
            cancelled++;
        }

        // 询价 CAS：CONFIRMED → VOIDED（并发唯一赢家；与登记出库联动/R4 回滚竞态由 CAS 决出）
        int cas = inquiryRequestMapper.update(null, new LambdaUpdateWrapper<InquiryRequest>()
                .eq(InquiryRequest::getId, inquiryId)
                .eq(InquiryRequest::getStatus, InquiryRequest.STATUS_CONFIRMED)
                .set(InquiryRequest::getStatus, InquiryRequest.STATUS_VOIDED)
                .set(InquiryRequest::getVoidedAt, now));
        if (cas != 1) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }

        // 通知 WK（仓库侧=租户联系人，含收回纸单提示）；RT 免登录无 user_id，站内信降级跳过（据实现备注）
        notificationService.send(inquiry.getTenantId(), tenantService.getContactUserId(inquiry.getTenantId()),
                com.cangchu.notify.entity.Notification.TYPE_INQUIRY_VOIDED, "意向单已作废",
                "意向单 " + inquiry.getDocNo() + " 已由商户作废，名下 " + cancelled + " 张出库单已撤销、库存已回补。"
                        + (anyPrinted ? "其中含已打印单，请收回现场纸质单。" : ""),
                com.cangchu.notify.entity.Notification.REF_INQUIRY, inquiry.getId());

        log.info("[P3][R8] WA {} 作废意向单 doc={} 联动撤销出库 {} 张", waUserId, inquiry.getDocNo(), cancelled);
        return loadVo(inquiryId);
    }

    // ==================== 列表 ====================

    @Override
    public List<InquiryVo> listForWa(Long tenantId, Long waUserId) {
        // 该用户作为 WA 归属的所有 wholesaler（跨租户集合，随后按 tenantId 过滤 inquiry）；
        // P2 Wave3：WE 员工同看本商户询价列表（只读不限授权位，确认在 requireWaRole 卡授权）
        List<Long> waWholesalerIds = new ArrayList<>(authService.listActiveWholesalerIds(waUserId, "WA"));
        waWholesalerIds.addAll(authService.listActiveWeWholesalerIds(waUserId));
        if (waWholesalerIds.isEmpty()) {
            return List.of();
        }
        List<InquiryRequest> reqs = inquiryRequestMapper.selectList(new LambdaQueryWrapper<InquiryRequest>()
                .eq(InquiryRequest::getTenantId, tenantId)
                .in(InquiryRequest::getWholesalerId, waWholesalerIds)
                .orderByDesc(InquiryRequest::getCreatedAt));
        List<InquiryVo> out = new ArrayList<>(reqs.size());
        for (InquiryRequest r : reqs) {
            out.add(toVo(r, loadItems(r.getId())));
        }
        return out;
    }

    // ==================== 跨域出口（P2 Wave2 R13） ====================

    @Override
    public long countOpenDocsForWholesaler(Long wholesalerId) {
        // 非终态询价：PENDING（待确认）/ CONFIRMED（P3 起为常驻中间态：名下出库单未全部登记）。
        // COMPLETED / VOIDED 为终态不计（12 §8.2）。
        long openInquiries = inquiryRequestMapper.selectCount(new LambdaQueryWrapper<InquiryRequest>()
                .eq(InquiryRequest::getWholesalerId, wholesalerId)
                .in(InquiryRequest::getStatus,
                        InquiryRequest.STATUS_PENDING, InquiryRequest.STATUS_CONFIRMED));
        // 非终态出库单（12 §8.2）：PENDING_ACCEPT/PRINTED/COMPLAINED 未结；
        // COMPLETED/WITHDRAWN/CANCELLED 视为已结（撤回/撤销已回补，不阻退驻）。
        long openOutbounds = outboundRequestMapper.selectCount(new LambdaQueryWrapper<OutboundRequest>()
                .eq(OutboundRequest::getWholesalerId, wholesalerId)
                .in(OutboundRequest::getStatus,
                        OutboundRequest.STATUS_PENDING_ACCEPT,
                        OutboundRequest.STATUS_PRINTED,
                        OutboundRequest.STATUS_COMPLAINED));
        return openInquiries + openOutbounds;
    }

    // ==================== 私有 ====================

    /**
     * S4：确认询价的操作人须为该 wholesaler 的 WA，或该商户持 INQUIRY_CONFIRM 授权位的
     * WE（P2 Wave3 切点，WEM-S1-05/S4-02）：未授权 WE → 42004，非本商户 → 50286。
     */
    private void requireWaRole(Long wholesalerId, Long userId) {
        if (authService.hasWholesalerRole(userId, "WA", wholesalerId)) {
            return;
        }
        if (authService.hasWholesalerRole(userId, "WE", wholesalerId)) {
            if (!authService.hasWholesalerPermission(userId, wholesalerId,
                    com.cangchu.common.util.WePermissions.INQUIRY_CONFIRM)) {
                throw new BizException(ErrorCode.PERMISSION_ROLE_004, "未获得询价确认授权，请联系商户管理员");
            }
            return;
        }
        throw new BizException(ErrorCode.INQUIRY_OPERATOR_NOT_WA);
    }

    /** 取租户简码用于 docNo；查不到则用 tenantId 占位。经 TenantService 取值（G-S2，不直连 TenantMapper）。 */
    private String resolveSimpleCode(Long tenantId) {
        String simpleCode = tenantService.getSimpleCode(tenantId);
        if (simpleCode != null && !simpleCode.isBlank()) {
            return simpleCode;
        }
        return "T" + tenantId;
    }

    private InquiryVo loadVo(Long inquiryId) {
        InquiryRequest r = inquiryRequestMapper.selectById(inquiryId);
        return toVo(r, loadItems(inquiryId));
    }

    private List<InquiryItem> loadItems(Long inquiryId) {
        return inquiryItemMapper.selectList(new LambdaQueryWrapper<InquiryItem>()
                .eq(InquiryItem::getInquiryId, inquiryId)
                .orderByAsc(InquiryItem::getId));
    }

    private InquiryVo toVo(InquiryRequest r, List<InquiryItem> items) {
        InquiryVo vo = new InquiryVo();
        vo.setId(r.getId());
        vo.setDocNo(r.getDocNo());
        vo.setStoreId(r.getStoreId());
        vo.setTenantId(r.getTenantId());
        vo.setWholesalerId(r.getWholesalerId());
        vo.setStatus(r.getStatus());
        vo.setRtPhone(r.getRtPhone());
        vo.setCreatedAt(r.getCreatedAt());
        vo.setConfirmedAt(r.getConfirmedAt());
        vo.setVoidedAt(r.getVoidedAt());
        vo.setItems(items.stream().map(it -> {
            InquiryVo.InquiryItemVo iv = new InquiryVo.InquiryItemVo();
            iv.setId(it.getId());
            iv.setSkuId(it.getSkuId());
            iv.setQty(it.getQty());
            iv.setUnitPriceSnapshot(it.getUnitPriceSnapshot());
            iv.setMoqPriceSnapshot(it.getMoqPriceSnapshot());
            iv.setMoqQtySnapshot(it.getMoqQtySnapshot());
            iv.setDealPrice(it.getDealPrice());
            return iv;
        }).toList());
        return vo;
    }
}
