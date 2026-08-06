package com.cangchu.document.service;

import com.cangchu.document.enums.DocType;

/**
 * 单据号生成服务（phase-1 C1 引入，C2 出库/询价复用）。
 *
 * <p>实现：Redis 原子自增（Redisson {@code RAtomicLong}），按「单据类型 + 租户 + 日期」分桶，
 * 当日序号从 1 起、当日结束过期。生成的 docNo 全局唯一（再叠加单据表 DB 唯一索引兜底，G-5.1）。
 */
public interface DocumentNumberService {

    /**
     * 生成单据号。
     *
     * @param docType          单据类型（决定前缀，如 INBOUND→WK）
     * @param tenantSimpleCode 租户简码（用于人读 + 分桶隔离不同租户的当日序号；可空则用占位）
     * @return docNo，形如 {@code WK-<简码>-yyyyMMdd-0001}
     */
    String generate(DocType docType, String tenantSimpleCode);

    /**
     * 月度账单号（P4 W3，14 §3.4 / D-P4-7=A）：{@code BL-{简码归一}-W{wholesalerId}-{yyyyMM}}，
     * 例 {@code BL-T8801-W17-202607}。无日序列、无 Redis 计数——(t,ws,月) 天然唯一，
     * uk_bill_no + uk_bill_idempotent 双层兜底（G-5.1 同构）。WS 段 = W{id} 兜底
     * （wholesalers 无简码列，零迁移；ArbitrationServiceImpl "T"+tenantId 先例）。
     *
     * @param tenantSimpleCode 租户简码（normalize 归一，同 {@link #generate}）
     * @param wholesalerId     批发商 id（WS 段兜底简码）
     * @param month            账期月
     */
    String generateBillNo(String tenantSimpleCode, Long wholesalerId, java.time.YearMonth month);
}
