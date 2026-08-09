package com.cangchu.billing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.billing.entity.Bill;
import com.cangchu.billing.entity.BillItem;
import com.cangchu.billing.entity.BillingRule;
import com.cangchu.billing.entity.DailySnapshot;
import com.cangchu.billing.mapper.BillItemMapper;
import com.cangchu.billing.mapper.BillMapper;
import com.cangchu.billing.mapper.BillingRuleMapper;
import com.cangchu.billing.mapper.DailySnapshotMapper;
import com.cangchu.billing.service.BillingService;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.inventory.entity.StockMovement;
import com.cangchu.inventory.mapper.StockMovementMapper;
import com.cangchu.tenant.dto.TenantApplyDto;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4 W5 导出 + TA 总览补口场景测试（14 §7 W5 关卡）：
 * Excel 解析回读（三金额/明细行数/回款/按日合计）/ PDF 魔数+非空+水印分支（未下发预览稿）/
 * 印章位 / 5000 行降级按货品聚合 / RFC 5987 中文文件名 / 对账单按日导出 /
 * bills-overview 汇总=账单表聚合断言 + requireTa / 越权矩阵（WE 42004、WK·WA 42001、跨租户 50370）。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BillExportScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private WholesalerMapper wholesalerMapper;
    @Autowired
    private StockMovementMapper stockMovementMapper;
    @Autowired
    private BillingRuleMapper billingRuleMapper;
    @Autowired
    private BillMapper billMapper;
    @Autowired
    private BillItemMapper billItemMapper;
    @Autowired
    private DailySnapshotMapper dailySnapshotMapper;
    @Autowired
    private BillingService billingService;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private static final String P_TA =
            "13" + String.format("%05d", ((System.nanoTime() >> 14) & 0x7FFFFFFF) % 100000);
    private static final String P_EMP =
            "15" + String.format("%05d", ((System.nanoTime() >> 8) & 0x7FFFFFFF) % 100000);
    private static final String P_WA =
            "16" + String.format("%05d", ((System.nanoTime() >> 2) & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String base;

    @BeforeEach
    void setUp() {
        base = "http://localhost:" + port;
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Object>>> MAP = new ParameterizedTypeReference<>() {};

    // ==================== helpers（BillLifecycleScenarioTest 同构） ====================

    private String uniquePhone(String prefix) {
        return prefix + String.format("%04d", SEQ.incrementAndGet() % 10000);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        return h;
    }

    private LoginVo registerAndLogin(String phone, String password, String role, String inviteCode) {
        RegisterDto dto = new RegisterDto();
        dto.setPhone(phone);
        dto.setPassword(password);
        dto.setSmsCode("888888");
        dto.setRole(role);
        dto.setAgreedTerms(true);
        if (inviteCode != null) dto.setInviteCode(inviteCode);
        R<LoginVo> body = restTemplate.exchange(base + "/api/v1/account/register", HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("register %s role=%s", phone, role).isEqualTo(0);
        return body.getData();
    }

    private record TaContext(String token, Long tenantId) {}

    private TaContext registerTaActive() {
        String phone = uniquePhone(P_TA);
        String token = registerAndLogin(phone, "TaPass123", "TA", null).getToken();
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("导出测试仓-" + phone);
        dto.setContactPhone(phone);
        dto.setAddressText("浙江省宁波市江北区");
        R<Map<String, Object>> body = restTemplate.exchange(base + "/api/v1/tenant/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        long tenantId = Long.parseLong(body.getData().get("tenantId").toString());
        Tenant tenant = tenantMapper.selectById(tenantId);
        tenant.setStatus("ACTIVE");
        tenantMapper.updateById(tenant);
        TenantContext.clear();
        return new TaContext(token, tenantId);
    }

    private LoginVo registerEmployee(TaContext ta, String role) {
        Map<String, Object> inviteDto = new LinkedHashMap<>();
        inviteDto.put("role", role);
        inviteDto.put("maxUses", 1);
        inviteDto.put("expiresInDays", 7);
        R<Map<String, Object>> invite = restTemplate.exchange(base + "/api/v1/tenant/employee-invites",
                HttpMethod.POST, new HttpEntity<>(inviteDto, bearer(ta.token())), MAP).getBody();
        assertThat(invite).isNotNull();
        assertThat(invite.getCode()).as("TA 生 %s 码", role).isEqualTo(0);
        String code = invite.getData().get("code").toString();
        return registerAndLogin(uniquePhone(P_EMP), "EmpPass123", role, code);
    }

    private record WaContext(String token, Long userId, Long wholesalerId) {}

    private WaContext onboardWa(TaContext ta) {
        String phone = uniquePhone(P_WA);
        LoginVo reg = registerAndLogin(phone, "WaPass123", "WA", null);
        Map<String, Object> apply = new LinkedHashMap<>();
        apply.put("targetTenantId", ta.tenantId().toString());
        apply.put("name", "导出商户-" + phone);
        R<Map<String, Object>> applied = restTemplate.exchange(base + "/api/v1/wholesaler/applications",
                HttpMethod.POST, new HttpEntity<>(apply, bearer(reg.getToken())), MAP).getBody();
        assertThat(applied).isNotNull();
        assertThat(applied.getCode()).isEqualTo(0);
        String appId = applied.getData().get("applicationId").toString();
        Map<String, Object> auditDto = Map.of("action", "APPROVED", "remark", "P4 W5 测试放行");
        R<Map<String, Object>> approved = restTemplate.exchange(
                base + "/api/v1/tenant/wholesaler-applications/" + appId + "/audit",
                HttpMethod.POST, new HttpEntity<>(auditDto, bearer(ta.token())), MAP).getBody();
        assertThat(approved).isNotNull();
        assertThat(approved.getCode()).isEqualTo(0);
        return new WaContext(reg.getToken(), reg.getUserId(),
                Long.parseLong(approved.getData().get("wholesalerId").toString()));
    }

    private Long seedWholesaler(TaContext ta, String name) {
        TenantContext.clear();
        Wholesaler ws = new Wholesaler();
        ws.setId(snowflakeIdUtil.nextId());
        ws.setTenantId(ta.tenantId());
        ws.setName(name);
        ws.setStatus("ACTIVE");
        ws.setOwnerUserId(snowflakeIdUtil.nextId());
        wholesalerMapper.insert(ws);
        return ws.getId();
    }

    private void seedRule(TaContext ta, LocalDate from) {
        TenantContext.clear();
        BillingRule rule = new BillingRule();
        rule.setId(snowflakeIdUtil.nextId());
        rule.setTenantId(ta.tenantId());
        rule.setQtyEnabled(1);
        rule.setPalletEnabled(0);
        rule.setPricePerQtyDay(new BigDecimal("1.0000"));
        rule.setMinCharge(BigDecimal.ZERO);
        rule.setEffectiveFrom(from);
        rule.setEffectiveTo(null);
        rule.setVersion(1);
        billingRuleMapper.insert(rule);
    }

    /** 造一张应收 10.00 的 DRAFT 账单（BillLifecycleScenarioTest 同构：上月倒数第 6 日入库 2 件） */
    private Bill seedDraftBill(TaContext ta, Long wsId) {
        YearMonth month = YearMonth.now().minusMonths(1);
        seedRule(ta, month.minusMonths(2).atDay(1));
        TenantContext.clear();
        StockMovement m = new StockMovement();
        m.setId(snowflakeIdUtil.nextId());
        m.setTenantId(ta.tenantId());
        m.setWholesalerId(wsId);
        m.setSkuId(snowflakeIdUtil.nextId());
        m.setType(StockMovement.TYPE_INBOUND);
        m.setQty(2);
        m.setBizTime(month.atDay(month.lengthOfMonth() - 5).atTime(10, 0));
        m.setPalletDelta(0);
        m.setCreatedAt(month.atDay(month.lengthOfMonth() - 5).atTime(10, 0));
        stockMovementMapper.insert(m);
        Bill bill = billingService.generateForPair(ta.tenantId(), wsId, month);
        assertThat(bill).isNotNull();
        assertThat(bill.getTotalAmount()).isEqualByComparingTo("10.00");
        return bill;
    }

    /** 直插账单行（overview 聚合断言用；金额/状态受控） */
    private Bill seedBillRow(Long tenantId, Long wsId, String month, String total, String paid, String status) {
        TenantContext.clear();
        Bill bill = new Bill();
        bill.setId(snowflakeIdUtil.nextId());
        bill.setBillNo("BL-W5T-" + bill.getId());
        bill.setTenantId(tenantId);
        bill.setWholesalerId(wsId);
        bill.setBillingMonth(month);
        bill.setPeriodStart(YearMonth.parse(month).atDay(1));
        bill.setPeriodEnd(YearMonth.parse(month).atEndOfMonth());
        bill.setSubtotalAmount(new BigDecimal(total));
        bill.setAdjustAmount(BigDecimal.ZERO);
        bill.setTotalAmount(new BigDecimal(total));
        bill.setPaidAmount(new BigDecimal(paid));
        bill.setStatus(status);
        bill.setIdempotentKey("w5t:" + bill.getId());
        billMapper.insert(bill);
        return bill;
    }

    private R<Map<String, Object>> post(String token, String path, Map<String, Object> body) {
        return restTemplate.exchange(base + path, HttpMethod.POST,
                new HttpEntity<>(body != null ? body : Map.of(), bearer(token)), MAP).getBody();
    }

    private R<Map<String, Object>> getJson(String token, String path) {
        return restTemplate.exchange(base + path, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), MAP).getBody();
    }

    private ResponseEntity<byte[]> download(String token, String path) {
        return restTemplate.exchange(base + path, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), byte[].class);
    }

    // ==================== Excel 读回 helpers ====================

    private XSSFWorkbook workbook(byte[] bytes) throws Exception {
        assertThat(bytes).as("导出响应体非空").isNotNull();
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
            // 非 zip：多半是服务端 JSON 错误体——直接把内容曝出来定位
            org.assertj.core.api.Assertions.fail("非 xlsx 响应（前 500 字节）："
                    + new String(bytes, 0, Math.min(500, bytes.length), StandardCharsets.UTF_8));
        }
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    /** 汇总类 KV Sheet 取值（col0=key → col1） */
    private String kv(Sheet sheet, String key) {
        for (Row row : sheet) {
            Cell c0 = row.getCell(0);
            if (c0 != null && c0.getCellType() == CellType.STRING && key.equals(c0.getStringCellValue())) {
                Cell c1 = row.getCell(1);
                if (c1 == null) {
                    return null;
                }
                return c1.getCellType() == CellType.NUMERIC
                        ? BigDecimal.valueOf(c1.getNumericCellValue()).stripTrailingZeros().toPlainString()
                        : c1.getStringCellValue();
            }
        }
        return null;
    }

    private BigDecimal kvMoney(Sheet sheet, String key) {
        String v = kv(sheet, key);
        assertThat(v).as("汇总键 %s", key).isNotNull();
        return new BigDecimal(v);
    }

    private String pdfText(byte[] bytes) throws Exception {
        try (PDDocument doc = PDDocument.load(bytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static BigDecimal num(Object v) {
        return new BigDecimal(String.valueOf(v));
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("EXP-01 Excel 导出流可读：四 Sheet 回读三金额/明细行数/回款/中文文件名 RFC 5987")
    void exp01_excelRoundTrip() throws Exception {
        TaContext ta = registerTaActive();
        String st = registerEmployee(ta, "ST").getToken();
        WaContext wa = onboardWa(ta);
        Bill bill = seedDraftBill(ta, wa.wholesalerId());
        Long id = bill.getId();
        // 下发 → WA 确认 → 部分回款 4.00
        assertThat(post(st, "/api/v1/tenant/st/bills/" + id + "/dispatch", null).getCode()).isEqualTo(0);
        assertThat(post(wa.token(), "/api/v1/wholesaler/bills/" + id + "/confirm", null).getCode()).isEqualTo(0);
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("amount", "4.00");
        pay.put("payAt", java.time.LocalDateTime.now().minusHours(1).withNano(0).toString());
        pay.put("payMethod", "BANK_TRANSFER");
        assertThat(post(st, "/api/v1/tenant/st/bills/" + id + "/payments", pay).getCode()).isEqualTo(0);

        ResponseEntity<byte[]> resp = download(st, "/api/v1/tenant/st/bills/" + id + "/export?format=excel");
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        String disposition = resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        // 文件名编码（RFC 5987）：中文百分号编码 + ASCII 兜底
        assertThat(disposition).contains("filename*=UTF-8''%E8%B4%A6%E5%8D%95-");
        assertThat(disposition).contains(".xlsx");
        assertThat(disposition).contains("filename=\"");
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
                .contains("spreadsheetml");

        try (XSSFWorkbook wb = workbook(resp.getBody())) {
            // 汇总：三金额 + 已收/未收；非 DRAFT 无预览稿标记
            Sheet summary = wb.getSheet("汇总");
            assertThat(summary).isNotNull();
            assertThat(kvMoney(summary, "仓储费小计（元）")).isEqualByComparingTo("10.00");
            assertThat(kvMoney(summary, "应收合计（元）")).isEqualByComparingTo("10.00");
            assertThat(kvMoney(summary, "已收（元）")).isEqualByComparingTo("4.00");
            assertThat(kvMoney(summary, "未收（元）")).isEqualByComparingTo("6.00");
            assertThat(kv(summary, "提示")).isNull();
            assertThat(kv(summary, "账单编号")).isEqualTo(bill.getBillNo());
            // 明细：行数 = bill_items 行数（header +1）
            TenantContext.clear();
            long itemCount = billItemMapper.selectCount(new LambdaQueryWrapper<BillItem>()
                    .eq(BillItem::getBillId, id));
            Sheet detail = wb.getSheet("明细");
            assertThat((long) detail.getLastRowNum()).isEqualTo(itemCount); // 0=header
            // 明细金额合计 = 小计（本单无调整）
            BigDecimal detailSum = BigDecimal.ZERO;
            for (int r = 1; r <= detail.getLastRowNum(); r++) {
                detailSum = detailSum.add(BigDecimal.valueOf(detail.getRow(r).getCell(8).getNumericCellValue()));
            }
            assertThat(detailSum).isEqualByComparingTo("10.00");
            // 回款：1 行有效 4.00
            Sheet pays = wb.getSheet("回款");
            assertThat(pays.getLastRowNum()).isEqualTo(1);
            assertThat(BigDecimal.valueOf(pays.getRow(1).getCell(2).getNumericCellValue()))
                    .isEqualByComparingTo("4.00");
            assertThat(pays.getRow(1).getCell(3).getStringCellValue()).isEqualTo("有效");
            // 按日 Sheet 存在（无快照数据 → 仅 header+合计）
            assertThat(wb.getSheet("按日")).isNotNull();
        }
    }

    @Test
    @DisplayName("EXP-02 PDF 导出：魔数+非空；水印分支（DRAFT 有「未下发预览稿」，下发后无）；印章位")
    void exp02_pdfWatermarkBranch() throws Exception {
        TaContext ta = registerTaActive();
        String st = registerEmployee(ta, "ST").getToken();
        WaContext wa = onboardWa(ta);
        Bill bill = seedDraftBill(ta, wa.wholesalerId());
        Long id = bill.getId();

        // DRAFT：预览稿水印
        ResponseEntity<byte[]> draft = download(st, "/api/v1/tenant/st/bills/" + id + "/export?format=pdf");
        assertThat(draft.getStatusCode().is2xxSuccessful()).isTrue();
        byte[] draftBytes = draft.getBody();
        assertThat(draftBytes).isNotNull();
        assertThat(draftBytes.length).isGreaterThan(500);
        assertThat(new String(draftBytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(draft.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).contains("application/pdf");
        assertThat(draft.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("filename*=UTF-8''%E8%B4%A6%E5%8D%95-").contains(".pdf");
        String draftText = pdfText(draftBytes);
        assertThat(draftText).contains("未下发预览稿");
        assertThat(draftText).contains("（仓库盖章处）");
        assertThat(draftText).contains(bill.getBillNo());
        assertThat(draftText).contains("10.00");

        // 下发后：水印消失，印章位仍在
        assertThat(post(st, "/api/v1/tenant/st/bills/" + id + "/dispatch", null).getCode()).isEqualTo(0);
        ResponseEntity<byte[]> formal = download(st, "/api/v1/tenant/st/bills/" + id + "/export?format=pdf");
        String formalText = pdfText(formal.getBody());
        assertThat(formalText).doesNotContain("未下发预览稿");
        assertThat(formalText).contains("（仓库盖章处）");
        assertThat(formalText).contains("已下发");
    }

    @Test
    @DisplayName("EXP-03 5000 行降级：明细超阈值按货品聚合 + 提示行；金额合计不失真")
    void exp03_degradeOver5000() throws Exception {
        TaContext ta = registerTaActive();
        String st = registerEmployee(ta, "ST").getToken();
        WaContext wa = onboardWa(ta);
        Bill bill = seedDraftBill(ta, wa.wholesalerId());
        Long id = bill.getId();
        // 追加 5000 行 STORAGE（两货品各 2500 行 × 0.01 元）→ 总行数 5001 > 5000。
        // JDBC 批量直插（秒级）：逐条 mapper.insert 需数分钟，长静默会撞
        // SessionActiveTimeoutTest 泄漏到 SaManager 静态配置的 active-timeout=3s（token 冻结 41001）
        TenantContext.clear();
        Long skuA = snowflakeIdUtil.nextId();
        Long skuB = snowflakeIdUtil.nextId();
        List<Object[]> batch = new java.util.ArrayList<>(5000);
        for (int i = 0; i < 5000; i++) {
            batch.add(new Object[]{snowflakeIdUtil.nextId(), ta.tenantId(), id,
                    BillItem.TYPE_STORAGE, i % 2 == 0 ? skuA : skuB, 1,
                    new BigDecimal("0.01"), "压测行"});
        }
        jdbcTemplate.batchUpdate("INSERT INTO bill_items "
                + "(id, tenant_id, bill_id, item_type, sku_id, qty_days, amount, description) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", batch);

        ResponseEntity<byte[]> resp = download(st, "/api/v1/tenant/st/bills/" + id + "/export?format=excel");
        try (XSSFWorkbook wb = workbook(resp.getBody())) {
            Sheet detail = wb.getSheet("明细");
            // 行0=提示行，行1=header，数据 = 按 (类型,货品) 聚合的 3 组（原 SKU + skuA + skuB）
            assertThat(detail.getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("明细行数过多，已按货品聚合");
            int dataRows = detail.getLastRowNum() - 1; // 减提示行；header 占行1
            assertThat(dataRows).isEqualTo(3);
            BigDecimal sum = BigDecimal.ZERO;
            for (int r = 2; r <= detail.getLastRowNum(); r++) {
                sum = sum.add(BigDecimal.valueOf(detail.getRow(r).getCell(8).getNumericCellValue()));
            }
            // 10.00 + 2500×0.01 + 2500×0.01 = 60.00（聚合不失真）
            assertThat(sum).isEqualByComparingTo("60.00");
        }
        // PDF 同口径降级（提示行文案在正文）
        String pdfText = pdfText(download(st, "/api/v1/tenant/st/bills/" + id + "/export?format=pdf").getBody());
        assertThat(pdfText).contains("明细行数过多，已按货品聚合");
    }

    @Test
    @DisplayName("EXP-04 对账单按日导出：日行数=整月天数、件数/金额合计与快照一致；中文文件名")
    void exp04_snapshotExport() throws Exception {
        TaContext ta = registerTaActive();
        String st = registerEmployee(ta, "ST").getToken();
        Long wsId = seedWholesaler(ta, "对账商户甲");
        YearMonth month = YearMonth.now().minusMonths(1);
        seedRule(ta, month.minusMonths(2).atDay(1));
        // 三日快照 qty=2（其余日缺行=0）
        TenantContext.clear();
        Long skuId = snowflakeIdUtil.nextId();
        for (int d = 1; d <= 3; d++) {
            DailySnapshot snap = new DailySnapshot();
            snap.setId(snowflakeIdUtil.nextId());
            snap.setTenantId(ta.tenantId());
            snap.setWholesalerId(wsId);
            snap.setSkuId(skuId);
            snap.setSnapshotDate(month.atDay(d));
            snap.setQty(2);
            snap.setPalletQty(0);
            dailySnapshotMapper.insert(snap);
        }

        ResponseEntity<byte[]> resp = download(st,
                "/api/v1/tenant/st/snapshots/export?month=" + month + "&wholesalerId=" + wsId);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        String disposition = resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        // 对账单 → %E5%AF%B9%E8%B4%A6%E5%8D%95
        assertThat(disposition).contains("filename*=UTF-8''%E5%AF%B9%E8%B4%A6%E5%8D%95-");
        try (XSSFWorkbook wb = workbook(resp.getBody())) {
            Sheet sheet = wb.getSheet("按日对账");
            assertThat(sheet).isNotNull();
            assertThat(kv(sheet, "批发商")).isEqualTo("对账商户甲");
            assertThat(kv(sheet, "对账月份")).isEqualTo(month.toString());
            // 行布局：0-3 信息、4 空、5 header、6.. 日行、末行合计
            int headerRow = 5;
            int lastRow = sheet.getLastRowNum();
            int dayRows = lastRow - headerRow - 1;
            assertThat(dayRows).isEqualTo(month.lengthOfMonth());
            Row total = sheet.getRow(lastRow);
            assertThat(total.getCell(0).getStringCellValue()).isEqualTo("合计");
            assertThat((long) total.getCell(1).getNumericCellValue()).isEqualTo(6L); // 2×3 日
            assertThat(BigDecimal.valueOf(total.getCell(3).getNumericCellValue()))
                    .isEqualByComparingTo("6.00"); // 1 元/件·天
        }
    }

    @Test
    @DisplayName("EXP-05 导出越权矩阵：WK/WA 42001、WE 42004、跨租户 50370/50230、非法格式 40001")
    void exp05_exportPermissionMatrix() {
        TaContext ta = registerTaActive();
        String st = registerEmployee(ta, "ST").getToken();
        String wk = registerEmployee(ta, "WK").getToken();
        WaContext wa = onboardWa(ta);
        Bill bill = seedDraftBill(ta, wa.wholesalerId());
        Long id = bill.getId();
        String export = "/api/v1/tenant/st/bills/" + id + "/export?format=pdf";

        // WK/WA 42001
        assertThat(getJson(wk, export).getCode()).as("WK 拒").isEqualTo(42001);
        assertThat(getJson(wa.token(), export).getCode()).as("WA 拒").isEqualTo(42001);
        // WE 42004（WA 生 WE 码，BillingRuleScenarioTest 同构）
        Map<String, Object> weInvite = new LinkedHashMap<>();
        weInvite.put("maxUses", 1);
        weInvite.put("expireDays", 7);
        R<Map<String, Object>> weInviteRes = restTemplate.exchange(
                base + "/api/v1/wholesaler/employee-invites",
                HttpMethod.POST, new HttpEntity<>(weInvite, bearer(wa.token())), MAP).getBody();
        assertThat(weInviteRes).isNotNull();
        assertThat(weInviteRes.getCode()).isEqualTo(0);
        String weToken = registerAndLogin(uniquePhone(P_EMP), "WePass123", "TA",
                weInviteRes.getData().get("code").toString()).getToken();
        assertThat(getJson(weToken, export).getCode()).as("WE 拒 42004").isEqualTo(42004);
        // 跨租户账单按不存在 50370；跨租户商户对账 50230
        TaContext other = registerTaActive();
        String otherSt = registerEmployee(other, "ST").getToken();
        assertThat(getJson(otherSt, export).getCode()).as("跨租户 50370").isEqualTo(50370);
        assertThat(getJson(otherSt, "/api/v1/tenant/st/snapshots/export?month="
                + YearMonth.now().minusMonths(1) + "&wholesalerId=" + wa.wholesalerId()).getCode())
                .as("跨租户商户 50230").isEqualTo(50230);
        // 非法格式 40001；TA 兼岗放行（ST/TA gate）
        assertThat(getJson(st, "/api/v1/tenant/st/bills/" + id + "/export?format=doc").getCode())
                .isEqualTo(40001);
        assertThat(download(ta.token(), export).getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @DisplayName("EXP-06 bills-overview：汇总=账单表聚合断言；逐商户行/状态分布/未收降序；月过滤")
    void exp06_billsOverviewAggregation() {
        TaContext ta = registerTaActive();
        Long ws1 = seedWholesaler(ta, "总览商户一");
        Long ws2 = seedWholesaler(ta, "总览商户二");
        String month = YearMonth.now().minusMonths(1).toString();
        String otherMonth = YearMonth.now().minusMonths(2).toString();
        seedBillRow(ta.tenantId(), ws1, month, "100.00", "40.00", Bill.STATUS_PARTIAL_PAID);
        seedBillRow(ta.tenantId(), ws1, month, "50.00", "50.00", Bill.STATUS_PAID);
        seedBillRow(ta.tenantId(), ws2, month, "30.00", "0.00", Bill.STATUS_DISPATCHED);
        seedBillRow(ta.tenantId(), ws2, otherMonth, "99.00", "0.00", Bill.STATUS_DRAFT);

        R<Map<String, Object>> res = getJson(ta.token(), "/api/v1/tenant/bills-overview?month=" + month);
        assertThat(res).isNotNull();
        assertThat(res.getCode()).isEqualTo(0);
        Map<String, Object> data = res.getData();

        // 汇总数 = 账单表聚合断言（独立聚合对照）
        TenantContext.clear();
        List<Bill> expected = billMapper.selectList(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getTenantId, ta.tenantId())
                .eq(Bill::getBillingMonth, month));
        BigDecimal expReceivable = expected.stream().map(Bill::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expReceived = expected.stream().map(Bill::getPaidAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(num(data.get("receivable"))).isEqualByComparingTo(expReceivable);
        assertThat(num(data.get("received"))).isEqualByComparingTo(expReceived);
        assertThat(num(data.get("outstanding"))).isEqualByComparingTo(expReceivable.subtract(expReceived));
        assertThat(num(data.get("billCount"))).isEqualByComparingTo(BigDecimal.valueOf(expected.size()));
        assertThat(num(data.get("receivable"))).isEqualByComparingTo("180.00");
        assertThat(num(data.get("received"))).isEqualByComparingTo("90.00");

        @SuppressWarnings("unchecked")
        Map<String, Object> statusCounts = (Map<String, Object>) data.get("statusCounts");
        assertThat(num(statusCounts.get("PARTIAL_PAID"))).isEqualByComparingTo("1");
        assertThat(num(statusCounts.get("PAID"))).isEqualByComparingTo("1");
        assertThat(num(statusCounts.get("DISPATCHED"))).isEqualByComparingTo("1");
        assertThat(statusCounts).doesNotContainKey("DRAFT"); // 其他月不计

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        assertThat(rows).hasSize(2);
        // 未收降序：ws1 未收 60 > ws2 未收 30
        assertThat(rows.get(0).get("wholesalerId")).isEqualTo(ws1.toString());
        assertThat(rows.get(0).get("wholesalerName")).isEqualTo("总览商户一");
        assertThat(num(rows.get(0).get("receivable"))).isEqualByComparingTo("150.00");
        assertThat(num(rows.get(0).get("received"))).isEqualByComparingTo("90.00");
        assertThat(num(rows.get(0).get("outstanding"))).isEqualByComparingTo("60.00");
        assertThat(num(rows.get(0).get("billCount"))).isEqualByComparingTo("2");
        assertThat(rows.get(1).get("wholesalerId")).isEqualTo(ws2.toString());
        assertThat(num(rows.get(1).get("outstanding"))).isEqualByComparingTo("30.00");

        // 缺省 month = 全部月份
        R<Map<String, Object>> all = getJson(ta.token(), "/api/v1/tenant/bills-overview");
        assertThat(all).isNotNull();
        assertThat(all.getCode()).isEqualTo(0);
        assertThat(num(all.getData().get("billCount"))).isEqualByComparingTo("4");
        assertThat(num(all.getData().get("receivable"))).isEqualByComparingTo("279.00");
        // 月格式非法 40001
        assertThat(getJson(ta.token(), "/api/v1/tenant/bills-overview?month=2026-13").getCode())
                .isEqualTo(40001);
    }

    @Test
    @DisplayName("EXP-07 bills-overview requireTa：ST/WK/WA 42001（TA 专属，兼岗不放行）")
    void exp07_overviewRequireTa() {
        TaContext ta = registerTaActive();
        String st = registerEmployee(ta, "ST").getToken();
        String wk = registerEmployee(ta, "WK").getToken();
        WaContext wa = onboardWa(ta);
        String path = "/api/v1/tenant/bills-overview";
        assertThat(getJson(st, path).getCode()).as("ST 拒（TA 专属）").isEqualTo(42001);
        assertThat(getJson(wk, path).getCode()).as("WK 拒").isEqualTo(42001);
        assertThat(getJson(wa.token(), path).getCode()).as("WA 拒").isEqualTo(42001);
        assertThat(getJson(ta.token(), path).getCode()).as("TA 放行").isEqualTo(0);
    }
}
