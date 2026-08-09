package com.cangchu.billing.export;

import com.cangchu.billing.entity.Bill;
import com.cangchu.billing.entity.PaymentRecord;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.OutputStream;
import java.time.format.DateTimeFormatter;

import static com.cangchu.billing.export.ExportSupport.money;
import static com.cangchu.billing.export.ExportSupport.xml;

/**
 * 账单 PDF 渲染（P4 W5，PRD 13-p4 §6 版式）：HTML 模板 + openhtmltopdf 同步流式输出。
 *
 * <p>版式：抬头（仓库名+账单编号+账期+批发商）→ 三金额汇总 → 明细行全量（降级=按货品聚合+提示行）
 * → 回款记录 → 落款区印章位（预留盖章空白框+「（仓库盖章处）」）+ 导出时间。
 * 预览稿（DRAFT）整页平铺「未下发预览稿」水印（position:fixed 逐页重复）。
 */
@Slf4j
public final class BillPdfRenderer {

    private BillPdfRenderer() {
    }

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 渲染 PDF 到输出流（同步流式，不落存储 D-P4-8=A） */
    public static void render(BillExportModel model, OutputStream out) throws Exception {
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        File cjk = ExportSupport.resolveCjkFont();
        if (cjk != null) {
            builder.useFont(cjk, "cjk", 400, BaseRendererBuilder.FontStyle.NORMAL, true);
        } else {
            // 部署 checklist 项：无系统中文字体时 PDF 中文字形缺失（文件仍有效）
            log.warn("[P4][导出] 未找到系统中文字体，PDF 中文字形将缺失（请安装黑体/Noto CJK）");
        }
        builder.withHtmlContent(buildHtml(model), null);
        builder.toStream(out);
        builder.run();
    }

    private static String buildHtml(BillExportModel m) {
        Bill bill = m.bill();
        StringBuilder h = new StringBuilder(8192);
        h.append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><style>")
                .append("@page { size: A4; margin: 16mm 13mm; } ")
                .append("body { font-family: 'cjk', sans-serif; font-size: 9.5pt; color: #222; } ")
                .append("h1 { font-size: 15pt; text-align: center; margin: 0 0 4pt 0; } ")
                .append(".sub { text-align: center; color: #555; margin-bottom: 10pt; } ")
                .append("table { width: 100%; border-collapse: collapse; margin-bottom: 10pt; } ")
                .append("th, td { border: 0.5pt solid #999; padding: 3pt 4pt; text-align: left; } ")
                .append("th { background-color: #f0f0f0; } ")
                .append(".num { text-align: right; } ")
                .append(".sec { font-size: 11pt; font-weight: bold; margin: 8pt 0 4pt 0; } ")
                .append(".note { color: #b45309; margin: 4pt 0; } ")
                .append(".watermark { position: fixed; top: 42%; left: 8%; width: 84%; text-align: center; ")
                .append("font-size: 40pt; color: #d9d9d9; transform: rotate(-20deg); } ")
                .append(".stampzone { page-break-inside: avoid; margin-top: 16pt; width: 100%; } ")
                .append(".stampbox { float: right; width: 150pt; height: 100pt; border: 1pt solid #888; ")
                .append("text-align: center; padding-top: 40pt; color: #666; } ")
                .append(".exporttime { float: left; color: #666; margin-top: 70pt; } ")
                .append("</style></head><body>");

        if (m.preview()) {
            h.append("<div class=\"watermark\">未下发预览稿</div>");
        }

        // 抬头（PRD §6：仓库名 + 账单编号 + 账期 + 批发商名）
        h.append("<h1>").append(xml(m.tenantName())).append(" 仓储费账单</h1>")
                .append("<div class=\"sub\">账单编号：").append(xml(bill.getBillNo()))
                .append("　账期：").append(xml(bill.getBillingMonth()))
                .append("（").append(bill.getPeriodStart()).append(" ~ ").append(bill.getPeriodEnd())
                .append("）</div>")
                .append("<table><tr>")
                .append("<th style=\"width:20%\">批发商</th><td style=\"width:46%\">")
                .append(xml(m.wholesalerName())).append("</td>")
                .append("<th style=\"width:14%\">账单状态</th><td>")
                .append(xml(ExportSupport.statusLabel(bill.getStatus()))).append("</td>")
                .append("</tr></table>");

        // 三金额汇总
        h.append("<div class=\"sec\">费用汇总</div><table><tr>")
                .append("<th>仓储费小计（元）</th><th>调整合计（元）</th><th>应收合计（元）</th>")
                .append("<th>已收（元）</th><th>未收（元）</th></tr><tr>")
                .append("<td class=\"num\">").append(money(bill.getSubtotalAmount())).append("</td>")
                .append("<td class=\"num\">").append(money(bill.getAdjustAmount())).append("</td>")
                .append("<td class=\"num\">").append(money(bill.getTotalAmount())).append("</td>")
                .append("<td class=\"num\">").append(money(bill.getPaidAmount())).append("</td>")
                .append("<td class=\"num\">")
                .append(money(bill.getTotalAmount().subtract(bill.getPaidAmount()))).append("</td>")
                .append("</tr></table>");

        // 明细
        h.append("<div class=\"sec\">费用明细</div>");
        if (m.degraded()) {
            h.append("<div class=\"note\">明细行数过多，已按货品聚合</div>");
        }
        h.append("<table><tr><th>类型</th><th>货品</th><th>期间</th>")
                .append("<th class=\"num\">件·天</th><th class=\"num\">托盘·天</th>")
                .append("<th class=\"num\">单价(件·天)</th><th class=\"num\">单价(托盘·天)</th>")
                .append("<th class=\"num\">金额（元）</th><th>说明</th></tr>");
        for (BillExportModel.DetailRow row : m.details()) {
            h.append("<tr><td>").append(xml(ExportSupport.itemTypeLabel(row.itemType()))).append("</td>")
                    .append("<td>").append(xml(row.skuName() != null ? row.skuName() : "-")).append("</td>")
                    .append("<td>").append(period(row)).append("</td>")
                    .append("<td class=\"num\">").append(numOrDash(row.qtyDays())).append("</td>")
                    .append("<td class=\"num\">").append(numOrDash(row.palletDays())).append("</td>")
                    .append("<td class=\"num\">")
                    .append(row.unitPriceQty() != null ? row.unitPriceQty().toPlainString() : "-").append("</td>")
                    .append("<td class=\"num\">")
                    .append(row.unitPricePallet() != null ? row.unitPricePallet().toPlainString() : "-").append("</td>")
                    .append("<td class=\"num\">").append(money(row.amount())).append("</td>")
                    .append("<td>").append(xml(row.description() != null ? row.description() : "")).append("</td></tr>");
        }
        h.append("</table>");

        // 回款记录
        h.append("<div class=\"sec\">回款记录</div>");
        if (m.payments().isEmpty()) {
            h.append("<div>暂无回款记录</div>");
        } else {
            h.append("<table><tr><th>收款日期</th><th>方式</th><th class=\"num\">金额（元）</th>")
                    .append("<th>状态</th><th>备注</th></tr>");
            for (PaymentRecord p : m.payments()) {
                boolean reversed = PaymentRecord.STATUS_REVERSED.equals(p.getStatus());
                h.append("<tr><td>").append(p.getPayAt() != null ? DT.format(p.getPayAt()) : "-").append("</td>")
                        .append("<td>").append(xml(ExportSupport.payMethodLabel(p.getPayMethod()))).append("</td>")
                        .append("<td class=\"num\">").append(money(p.getAmount())).append("</td>")
                        .append("<td>").append(reversed ? "已冲销" : "有效").append("</td>")
                        .append("<td>").append(xml(reversed && p.getReverseReason() != null
                                ? "冲销原因：" + p.getReverseReason()
                                : p.getRemark() != null ? p.getRemark() : "")).append("</td></tr>");
            }
            h.append("</table>");
        }

        // 落款区：印章位 + 导出时间（PRD §6）
        h.append("<div class=\"stampzone\">")
                .append("<div class=\"exporttime\">导出时间：").append(DT.format(m.exportedAt())).append("</div>")
                .append("<div class=\"stampbox\">（仓库盖章处）</div>")
                .append("</div>");

        return h.append("</body></html>").toString();
    }

    private static String period(BillExportModel.DetailRow row) {
        if (row.periodStart() == null && row.periodEnd() == null) {
            return "-";
        }
        return (row.periodStart() != null ? row.periodStart().toString() : "")
                + " ~ " + (row.periodEnd() != null ? row.periodEnd().toString() : "");
    }

    private static String numOrDash(Long v) {
        return v != null ? v.toString() : "-";
    }
}
