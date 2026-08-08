package com.cangchu.billing.export;

import com.cangchu.billing.entity.Bill;
import com.cangchu.billing.entity.PaymentRecord;
import com.cangchu.billing.vo.DailyBreakdownRowVo;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.cangchu.billing.export.ExportSupport.itemTypeLabel;
import static com.cangchu.billing.export.ExportSupport.payMethodLabel;
import static com.cangchu.billing.export.ExportSupport.statusLabel;

/**
 * 账单/对账单 Excel 输出（P4 W5，PRD 13-p4 §6 版式）：POI SXSSF 滚动窗口流式写响应流，
 * 不落存储（D-P4-8=A）。账单=汇总/明细/按日/回款四 Sheet；对账单=按日明细单 Sheet。
 */
public final class BillExcelWriter {

    private BillExcelWriter() {
    }

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** SXSSF 内存滚动窗口行数 */
    private static final int WINDOW = 100;

    /** 账单四 Sheet 工作簿（汇总+明细+按日+回款）流式写出 */
    public static void writeBill(BillExportModel m, OutputStream out) throws IOException {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(WINDOW)) {
            writeSummarySheet(wb, m);
            writeDetailSheet(wb, m);
            writeDailyRows(wb.createSheet("按日"), 0, m.dailyRows());
            writePaymentSheet(wb, m.payments());
            wb.write(out);
            wb.dispose();
        }
    }

    /** 对账单（按日明细）单 Sheet 工作簿流式写出 */
    public static void writeSnapshots(String tenantName, String wholesalerName, String month,
                                      List<DailyBreakdownRowVo> rows, LocalDateTime exportedAt,
                                      OutputStream out) throws IOException {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(WINDOW)) {
            Sheet sheet = wb.createSheet("按日对账");
            int r = 0;
            kv(sheet, r++, "仓库", tenantName);
            kv(sheet, r++, "批发商", wholesalerName);
            kv(sheet, r++, "对账月份", month);
            kv(sheet, r++, "导出时间", DT.format(exportedAt));
            r++; // 空行
            writeDailyRows(sheet, r, rows);
            wb.write(out);
            wb.dispose();
        }
    }

    // ==================== 账单四 Sheet ====================

    private static void writeSummarySheet(SXSSFWorkbook wb, BillExportModel m) {
        Sheet sheet = wb.createSheet("汇总");
        Bill bill = m.bill();
        int r = 0;
        if (m.preview()) {
            kv(sheet, r++, "提示", "未下发预览稿");
        }
        kv(sheet, r++, "仓库", m.tenantName());
        kv(sheet, r++, "账单编号", bill.getBillNo());
        kv(sheet, r++, "账期月", bill.getBillingMonth());
        kv(sheet, r++, "账期起", str(bill.getPeriodStart()));
        kv(sheet, r++, "账期止", str(bill.getPeriodEnd()));
        kv(sheet, r++, "批发商", m.wholesalerName());
        kv(sheet, r++, "状态", statusLabel(bill.getStatus()));
        kvMoney(sheet, r++, "仓储费小计（元）", bill.getSubtotalAmount());
        kvMoney(sheet, r++, "调整合计（元）", bill.getAdjustAmount());
        kvMoney(sheet, r++, "应收合计（元）", bill.getTotalAmount());
        kvMoney(sheet, r++, "已收（元）", bill.getPaidAmount());
        kvMoney(sheet, r++, "未收（元）", bill.getTotalAmount().subtract(bill.getPaidAmount()));
        kv(sheet, r, "导出时间", DT.format(m.exportedAt()));
    }

    private static void writeDetailSheet(SXSSFWorkbook wb, BillExportModel m) {
        Sheet sheet = wb.createSheet("明细");
        int r = 0;
        if (m.degraded()) {
            sheet.createRow(r++).createCell(0).setCellValue("明细行数过多，已按货品聚合");
        }
        header(sheet.createRow(r++), "类型", "货品", "期间起", "期间止", "件·天", "托盘·天",
                "单价(件·天)", "单价(托盘·天)", "金额（元）", "说明");
        for (BillExportModel.DetailRow d : m.details()) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(itemTypeLabel(d.itemType()));
            row.createCell(1).setCellValue(d.skuName() != null ? d.skuName() : "-");
            row.createCell(2).setCellValue(str(d.periodStart()));
            row.createCell(3).setCellValue(str(d.periodEnd()));
            num(row, 4, d.qtyDays());
            num(row, 5, d.palletDays());
            money(row, 6, d.unitPriceQty());
            money(row, 7, d.unitPricePallet());
            money(row, 8, d.amount());
            row.createCell(9).setCellValue(d.description() != null ? d.description() : "");
        }
    }

    private static void writeDailyRows(Sheet sheet, int startRow, List<DailyBreakdownRowVo> rows) {
        int r = startRow;
        header(sheet.createRow(r++), "日期", "计费件数", "计费托盘数", "当日金额（元）");
        long qtySum = 0;
        long palletSum = 0;
        BigDecimal amountSum = BigDecimal.ZERO;
        for (DailyBreakdownRowVo d : rows) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(str(d.getDate()));
            num(row, 1, d.getQty() != null ? d.getQty().longValue() : null);
            num(row, 2, d.getPalletQty() != null ? d.getPalletQty().longValue() : null);
            money(row, 3, d.getAmount());
            qtySum += d.getQty() != null ? d.getQty() : 0;
            palletSum += d.getPalletQty() != null ? d.getPalletQty() : 0;
            amountSum = amountSum.add(d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO);
        }
        Row total = sheet.createRow(r);
        total.createCell(0).setCellValue("合计");
        num(total, 1, qtySum);
        num(total, 2, palletSum);
        money(total, 3, amountSum);
    }

    private static void writePaymentSheet(SXSSFWorkbook wb, List<PaymentRecord> payments) {
        Sheet sheet = wb.createSheet("回款");
        int r = 0;
        header(sheet.createRow(r++), "收款日期", "方式", "金额（元）", "状态", "备注", "冲销原因");
        for (PaymentRecord p : payments) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(p.getPayAt() != null ? DT.format(p.getPayAt()) : "-");
            row.createCell(1).setCellValue(payMethodLabel(p.getPayMethod()));
            money(row, 2, p.getAmount());
            row.createCell(3).setCellValue(
                    PaymentRecord.STATUS_REVERSED.equals(p.getStatus()) ? "已冲销" : "有效");
            row.createCell(4).setCellValue(p.getRemark() != null ? p.getRemark() : "");
            row.createCell(5).setCellValue(p.getReverseReason() != null ? p.getReverseReason() : "");
        }
    }

    // ==================== 基元 ====================

    private static void header(Row row, String... titles) {
        for (int i = 0; i < titles.length; i++) {
            row.createCell(i).setCellValue(titles[i]);
        }
    }

    private static void kv(Sheet sheet, int rowIdx, String key, String value) {
        Row row = sheet.createRow(rowIdx);
        row.createCell(0).setCellValue(key);
        row.createCell(1).setCellValue(value != null ? value : "-");
    }

    private static void kvMoney(Sheet sheet, int rowIdx, String key, BigDecimal value) {
        Row row = sheet.createRow(rowIdx);
        row.createCell(0).setCellValue(key);
        money(row, 1, value);
    }

    private static void num(Row row, int col, Long v) {
        Cell cell = row.createCell(col);
        if (v != null) {
            cell.setCellValue(v);
        } else {
            cell.setCellValue("-");
        }
    }

    private static void money(Row row, int col, BigDecimal v) {
        Cell cell = row.createCell(col);
        if (v != null) {
            cell.setCellValue(v.doubleValue());
        } else {
            cell.setCellValue("-");
        }
    }

    private static String str(LocalDate d) {
        return d != null ? d.toString() : "-";
    }
}
