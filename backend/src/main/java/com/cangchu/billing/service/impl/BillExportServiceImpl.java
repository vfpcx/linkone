package com.cangchu.billing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.account.service.AuthService;
import com.cangchu.billing.entity.Bill;
import com.cangchu.billing.entity.BillItem;
import com.cangchu.billing.entity.PaymentRecord;
import com.cangchu.billing.export.BillExcelWriter;
import com.cangchu.billing.export.BillExportModel;
import com.cangchu.billing.export.BillPdfRenderer;
import com.cangchu.billing.export.ExportSupport;
import com.cangchu.billing.mapper.BillItemMapper;
import com.cangchu.billing.mapper.BillMapper;
import com.cangchu.billing.mapper.PaymentRecordMapper;
import com.cangchu.billing.service.BillExportService;
import com.cangchu.billing.service.DailySnapshotService;
import com.cangchu.billing.vo.DailyBreakdownRowVo;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.product.service.SkuService;
import com.cangchu.product.vo.SkuVo;
import com.cangchu.tenant.service.TenantService;
import com.cangchu.tenant.service.WholesalerService;
import com.cangchu.tenant.vo.WholesalerVo;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账单/对账单导出实现（P4 W5，14 §7 W5）。
 *
 * <p>安全自检（05-secure-coding-guardrails）：
 * <ul>
 *   <li>S4 越权：requireStOrTa（W1-W3 gate 同构，WE 42004/其余 42001）；账单跨租户/不存在
 *       50370 按不存在；对账商户归属 50230（DailySnapshotServiceImpl 同构）。</li>
 *   <li>S 头注入：Content-Disposition 文件名 RFC 5987 百分号编码 + ASCII 兜底
 *       （{@link ExportSupport#contentDisposition}），商户名先 sanitize。</li>
 *   <li>审计：结构化操作日志（谁/何时/哪张/格式，actor_role=ST 先例，14 §3.3 口径）。</li>
 * </ul>
 *
 * <p>流式（D-P4-8=A）：鉴权与数据装配全部完成后才写响应头/响应体，业务异常仍走统一 JSON；
 * Excel=SXSSF 滚动窗口，PDF=openhtmltopdf 直写响应流，均不落存储。明细超
 * {@link BillExportService#DETAIL_DEGRADE_THRESHOLD} 行降级按货品聚合 + 文件内提示行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillExportServiceImpl implements BillExportService {

    private final BillMapper billMapper;
    private final BillItemMapper billItemMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final DailySnapshotService dailySnapshotService;
    private final TenantService tenantService;
    private final WholesalerService wholesalerService;
    private final SkuService skuService;
    private final AuthService authService;

    private static final String CONTENT_TYPE_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String CONTENT_TYPE_PDF = "application/pdf";

    // ==================== 账单导出 ====================

    @Override
    public void exportBill(Long userId, Long billId, String format, HttpServletResponse response) {
        Long tenantId = requireStOrTa(userId);
        boolean pdf = "pdf".equalsIgnoreCase(format);
        if (!pdf && !"excel".equalsIgnoreCase(format)) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "导出格式仅支持 pdf/excel");
        }
        Bill bill = billMapper.selectById(billId);
        if (bill == null || !tenantId.equals(bill.getTenantId())) {
            // 跨租户/不存在按不存在（50370，不泄漏存在性）
            throw new BizException(ErrorCode.BILL_NOT_FOUND);
        }
        BillExportModel model = assembleModel(userId, bill);

        // P4-L3：字体先于响应头解析——缺失时附警告头（FE 可 toast），文档内另渲染英文提示行
        java.io.File cjkFont = pdf ? ExportSupport.resolveCjkFont() : null;

        String filename = "账单-" + ExportSupport.sanitizeFilePart(bill.getBillNo())
                + (pdf ? ".pdf" : ".xlsx");
        try {
            response.setContentType(pdf ? CONTENT_TYPE_PDF : CONTENT_TYPE_XLSX);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ExportSupport.contentDisposition(filename));
            if (pdf && cjkFont == null) {
                response.setHeader(HEADER_EXPORT_WARNING, WARNING_CJK_FONT_MISSING);
            }
            if (pdf) {
                BillPdfRenderer.render(model, cjkFont, response.getOutputStream());
            } else {
                BillExcelWriter.writeBill(model, response.getOutputStream());
            }
            response.flushBuffer();
        } catch (Exception e) {
            // 已开始写流后不可回退为 JSON，记日志（客户端表现为下载中断）
            log.error("[P4][导出] 账单 {} 导出失败（format={}）", bill.getBillNo(), format, e);
            throw new BizException(ErrorCode.SYSTEM_INTERNAL_001, "导出失败，请稍后重试");
        }
        // 导出审计：谁/何时(日志时间戳)/哪张/格式/预览稿（14 §3.3 操作日志 actor_role=ST 先例）
        log.info("[P4][导出][审计][actor_role=ST] 用户 {} 导出账单 {}（billId={} format={} 预览稿={} 降级聚合={}）",
                userId, bill.getBillNo(), bill.getId(), pdf ? "pdf" : "excel",
                model.preview(), model.degraded());
    }

    // ==================== 对账单（按日）导出 ====================

    @Override
    public void exportSnapshots(Long userId, Long wholesalerId, String month, HttpServletResponse response) {
        Long tenantId = requireStOrTa(userId);
        YearMonth ym = parseMonth(month);
        WholesalerVo ws = wholesalerId != null ? wholesalerService.getById(wholesalerId) : null;
        if (ws == null || !tenantId.equals(ws.getTenantId())) {
            // 商户跨租户/不存在统一 50230（DailySnapshotServiceImpl 同构）
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }
        // 按日明细（快照聚合，当段单价现算；不暴露实时库存 05 §5.4）
        List<DailyBreakdownRowVo> rows =
                dailySnapshotService.dailyBreakdown(userId, wholesalerId, ym.toString(), null);
        String tenantName = tenantName(tenantId);
        String wsName = ws.getName() != null ? ws.getName() : String.valueOf(wholesalerId);

        String filename = "对账单-" + ExportSupport.sanitizeFilePart(wsName) + "-" + ym + ".xlsx";
        try {
            response.setContentType(CONTENT_TYPE_XLSX);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ExportSupport.contentDisposition(filename));
            BillExcelWriter.writeSnapshots(tenantName, wsName, ym.toString(), rows,
                    LocalDateTime.now(), response.getOutputStream());
            response.flushBuffer();
        } catch (Exception e) {
            log.error("[P4][导出] 对账单导出失败 tenant={} ws={} month={}", tenantId, wholesalerId, ym, e);
            throw new BizException(ErrorCode.SYSTEM_INTERNAL_001, "导出失败，请稍后重试");
        }
        log.info("[P4][导出][审计][actor_role=ST] 用户 {} 导出对账单（ws={} month={} 行数={}）",
                userId, wholesalerId, ym, rows.size());
    }

    // ==================== 装配 ====================

    /** 装配导出内容模型：明细全量（>5000 行降级按货品聚合）+ 回款 + 按日 */
    private BillExportModel assembleModel(Long userId, Bill bill) {
        List<BillItem> items = billItemMapper.selectList(new LambdaQueryWrapper<BillItem>()
                .eq(BillItem::getBillId, bill.getId())
                .orderByAsc(BillItem::getId));
        boolean degraded = items.size() > DETAIL_DEGRADE_THRESHOLD;
        Map<Long, String> skuNames = new HashMap<>();
        List<BillExportModel.DetailRow> details = degraded
                ? aggregateBySku(items, skuNames)
                : items.stream().map(i -> toRow(i, skuNames)).toList();
        List<PaymentRecord> payments = paymentRecordMapper.selectList(
                new LambdaQueryWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getBillId, bill.getId())
                        .orderByAsc(PaymentRecord::getCreatedAt)
                        .orderByAsc(PaymentRecord::getId));
        // 按日 Sheet（快照聚合；Service 内含 requireStOrTa + 商户归属校验）
        List<DailyBreakdownRowVo> dailyRows = dailySnapshotService.dailyBreakdown(
                userId, bill.getWholesalerId(), bill.getBillingMonth(), null);
        WholesalerVo ws = wholesalerService.getById(bill.getWholesalerId());
        return new BillExportModel(
                tenantName(bill.getTenantId()),
                ws != null && ws.getName() != null ? ws.getName() : String.valueOf(bill.getWholesalerId()),
                bill,
                Bill.STATUS_DRAFT.equals(bill.getStatus()),
                degraded,
                details,
                payments,
                dailyRows,
                LocalDateTime.now());
    }

    private BillExportModel.DetailRow toRow(BillItem i, Map<Long, String> skuNames) {
        return new BillExportModel.DetailRow(
                i.getItemType(),
                i.getSkuId() != null ? skuName(i.getSkuId(), skuNames) : null,
                i.getPeriodStart(), i.getPeriodEnd(),
                i.getQtyDays() != null ? i.getQtyDays().longValue() : null,
                i.getPalletDays() != null ? i.getPalletDays().longValue() : null,
                i.getUnitPriceQty(), i.getUnitPricePallet(),
                i.getAmount(), i.getDescription());
    }

    /**
     * 降级聚合（D-P4-8）：按 (类型, 货品) 归并求和（件·天/托盘·天/金额），期间取并集；
     * 单价/说明不再逐行透出（聚合后无单一值）。
     */
    private List<BillExportModel.DetailRow> aggregateBySku(List<BillItem> items, Map<Long, String> skuNames) {
        record Key(String type, Long skuId) {}
        final class Acc {
            java.time.LocalDate start;
            java.time.LocalDate end;
            long qtyDays;
            long palletDays;
            boolean hasQtyDays;
            boolean hasPalletDays;
            BigDecimal amount = BigDecimal.ZERO;
            int count;
        }
        Map<Key, Acc> groups = new LinkedHashMap<>();
        for (BillItem i : items) {
            Acc acc = groups.computeIfAbsent(new Key(i.getItemType(), i.getSkuId()), k -> new Acc());
            if (i.getPeriodStart() != null && (acc.start == null || i.getPeriodStart().isBefore(acc.start))) {
                acc.start = i.getPeriodStart();
            }
            if (i.getPeriodEnd() != null && (acc.end == null || i.getPeriodEnd().isAfter(acc.end))) {
                acc.end = i.getPeriodEnd();
            }
            if (i.getQtyDays() != null) {
                acc.qtyDays += i.getQtyDays();
                acc.hasQtyDays = true;
            }
            if (i.getPalletDays() != null) {
                acc.palletDays += i.getPalletDays();
                acc.hasPalletDays = true;
            }
            if (i.getAmount() != null) {
                acc.amount = acc.amount.add(i.getAmount());
            }
            acc.count++;
        }
        List<BillExportModel.DetailRow> rows = new ArrayList<>(groups.size());
        groups.forEach((key, acc) -> rows.add(new BillExportModel.DetailRow(
                key.type(),
                key.skuId() != null ? skuName(key.skuId(), skuNames) : null,
                acc.start, acc.end,
                acc.hasQtyDays ? acc.qtyDays : null,
                acc.hasPalletDays ? acc.palletDays : null,
                null, null,
                acc.amount,
                "聚合 " + acc.count + " 行")));
        return rows;
    }

    // ==================== 私有 ====================

    private String tenantName(Long tenantId) {
        String name = tenantService.getTenantName(tenantId);
        return name != null ? name : "仓库";
    }

    private String skuName(Long skuId, Map<Long, String> cache) {
        return cache.computeIfAbsent(skuId, id -> {
            SkuVo sku = skuService.getById(id);
            return sku != null && sku.getName() != null ? sku.getName() : String.valueOf(id);
        });
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "请指定账期月（yyyy-MM）");
        }
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "账期月格式无效（yyyy-MM）");
        }
    }

    /** 读 gate：ST 或 TA 兼岗（billing 域 gate，W1-W3 同构）；WE 42004、其余 42001 */
    private Long requireStOrTa(Long userId) {
        Long tenantId = authService.findBoundTenantId(userId, "TA");
        if (tenantId == null) {
            tenantId = authService.findBoundTenantId(userId, "ST");
        }
        if (tenantId == null) {
            if (authService.hasRole(userId, "WE")) {
                throw new BizException(ErrorCode.PERMISSION_ROLE_004);
            }
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅仓库管理员或结算员可导出账单");
        }
        return tenantId;
    }
}
