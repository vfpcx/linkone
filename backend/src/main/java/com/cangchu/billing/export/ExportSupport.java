package com.cangchu.billing.export;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 导出公共支撑（P4 W5，14 §7 W5 / PRD 13-p4 §6）。
 *
 * <p>文件名编码走 RFC 5987：{@code filename} 落 ASCII 兜底（非 ASCII 以 _ 替换，兼容老客户端），
 * {@code filename*=UTF-8''...} 落百分号编码中文名（现代浏览器取此值）。百分号编码天然免疫
 * 头注入（CRLF/引号不可能出现，S 类自检）。
 *
 * <p>中文字体（PDF）：同步流式不落存储（D-P4-8=A），字体不随包分发（体积/授权考量），
 * 运行机按候选路径解析系统 TTF（Windows 黑体/仿宋、Linux Noto/文泉驿）；全部缺失时降级
 * 无 CJK 字体渲染（PDF 仍有效，中文字形缺失记 WARN——部署 checklist 项）。
 */
public final class ExportSupport {

    private ExportSupport() {
    }

    /** 账单状态中文文案（PRD 13-p4 §3.1 状态表） */
    private static final Map<String, String> STATUS_LABELS = Map.of(
            "DRAFT", "待核对",
            "DISPATCHED", "已下发",
            "PENDING_PAYMENT", "待回款",
            "PARTIAL_PAID", "部分回款",
            "PAID", "已结清",
            "DISPUTED", "争议中");

    /** 收款方式中文文案 */
    private static final Map<String, String> PAY_METHOD_LABELS = Map.of(
            "BANK_TRANSFER", "银行转账",
            "CASH", "现金",
            "WX", "微信",
            "ALIPAY", "支付宝",
            "OTHER", "其他");

    /** 条目类型中文文案 */
    private static final Map<String, String> ITEM_TYPE_LABELS = Map.of(
            "STORAGE", "仓储费",
            "ADJUSTMENT", "调整",
            "REVERSAL", "冲销",
            "STOCKTAKE_IMPACT", "盘点影响");

    /**
     * CJK 字体候选链覆写系统属性（P4-L3 测试/部署注入口）：逗号分隔路径列表。
     * 设置后完全替代内置候选链——指向不存在路径即可模拟「无中文字体」环境（降级测试用）。
     */
    public static final String CJK_FONT_PATHS_PROPERTY = "cangchu.export.cjk-font-paths";

    /** CJK 字体候选路径（按序取首个存在者） */
    private static final List<String> CJK_FONT_CANDIDATES = List.of(
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/simfang.ttf",
            "C:/Windows/Fonts/simkai.ttf",
            "/usr/share/fonts/truetype/arphic/uming.ttf",
            "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf",
            "/System/Library/Fonts/STHeiti Light.ttc");

    public static String statusLabel(String status) {
        return STATUS_LABELS.getOrDefault(status, status != null ? status : "-");
    }

    public static String payMethodLabel(String method) {
        return PAY_METHOD_LABELS.getOrDefault(method, method != null ? method : "-");
    }

    public static String itemTypeLabel(String itemType) {
        return ITEM_TYPE_LABELS.getOrDefault(itemType, itemType != null ? itemType : "-");
    }

    /**
     * Content-Disposition 值（RFC 5987/6266）：
     * {@code attachment; filename="ascii兜底"; filename*=UTF-8''百分号编码}。
     */
    public static String contentDisposition(String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        StringBuilder ascii = new StringBuilder(filename.length());
        for (int i = 0; i < filename.length(); i++) {
            char c = filename.charAt(i);
            ascii.append(c >= 0x20 && c <= 0x7E && c != '"' && c != '\\' ? c : '_');
        }
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    /** 文件名成分清洗：去路径分隔/控制字符（商户名等外部输入进文件名前必经） */
    public static String sanitizeFilePart(String part) {
        if (part == null || part.isBlank()) {
            return "未命名";
        }
        return part.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
    }

    /** 解析系统 CJK 字体；无可用字体返回 null（调用侧降级：警告响应头 + 文档内英文提示行） */
    public static File resolveCjkFont() {
        for (String candidate : fontCandidates()) {
            File f = new File(candidate);
            if (f.isFile() && f.canRead()) {
                return f;
            }
        }
        return null;
    }

    /** 候选链：系统属性覆写优先（{@link #CJK_FONT_PATHS_PROPERTY}），否则内置列表 */
    private static List<String> fontCandidates() {
        String override = System.getProperty(CJK_FONT_PATHS_PROPERTY);
        if (override == null) {
            return CJK_FONT_CANDIDATES;
        }
        return java.util.Arrays.stream(override.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** XHTML 文本转义（PDF 模板拼接必经，商户名/说明为外部输入） */
    public static String xml(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> {
                    if (c >= 0x20 || c == '\n') {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 金额显示（2 位小数；null 按 0.00） */
    public static String money(BigDecimal v) {
        return (v != null ? v : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
