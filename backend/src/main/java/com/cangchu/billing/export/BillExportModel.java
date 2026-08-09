package com.cangchu.billing.export;

import com.cangchu.billing.entity.Bill;
import com.cangchu.billing.entity.PaymentRecord;
import com.cangchu.billing.vo.DailyBreakdownRowVo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 账单导出内容模型（P4 W5）：Service 装配（含 5000 行降级归并），Excel/PDF 两渲染器共用，
 * 保证双格式内容口径一致（三金额/明细/回款/按日）。
 */
public record BillExportModel(
        String tenantName,
        String wholesalerName,
        Bill bill,
        /** 预览稿（DRAFT 未下发，PDF 水印 / Excel 标记行，PRD §6） */
        boolean preview,
        /** 明细超 5000 行已按货品聚合（文件内注明提示行，D-P4-8） */
        boolean degraded,
        List<DetailRow> details,
        List<PaymentRecord> payments,
        List<DailyBreakdownRowVo> dailyRows,
        LocalDateTime exportedAt) {

    /** 明细行（含分段/调整/冲销/盘点影响全量；降级态为按货品聚合行） */
    public record DetailRow(
            String itemType,
            String skuName,
            LocalDate periodStart,
            LocalDate periodEnd,
            Long qtyDays,
            Long palletDays,
            BigDecimal unitPriceQty,
            BigDecimal unitPricePallet,
            BigDecimal amount,
            String description) {
    }
}
