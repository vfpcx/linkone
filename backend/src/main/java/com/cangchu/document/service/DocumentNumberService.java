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
}
