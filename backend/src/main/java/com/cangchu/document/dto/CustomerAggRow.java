package com.cangchu.document.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * wa 客户列表聚合行（inquiry_requests 按 wholesaler × rt_phone_hmac 分组，24-p5-c-c3 §4.1）。
 *
 * <p>MyBatis-Plus 全局 map-underscore-to-camel-case=true，@Select 列别名自动映射到本类字段。
 * rtPhoneCipher 取组内任一行密文（同一明文不同随机 IV 的密文，解密结果一致 → 打码稳定）。
 */
@Data
public class CustomerAggRow {

    private String rtPhoneHmac;

    private Long wholesalerId;

    private Long inquiryCount;

    private LocalDateTime lastInquiryAt;

    private LocalDateTime lastConfirmedAt;

    /** 最新询价单 id（MAX(id)：雪花单调 ≈ 最新单；属组内行，可作 PII-REVEAL biz=INQUIRY 锚点） */
    private Long lastInquiryId;

    private String rtPhoneCipher;
}
