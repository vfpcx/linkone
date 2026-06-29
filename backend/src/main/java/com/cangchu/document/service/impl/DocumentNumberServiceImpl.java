package com.cangchu.document.service.impl;

import com.cangchu.document.enums.DocType;
import com.cangchu.document.service.DocumentNumberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 单据号生成实现：Redisson {@code RAtomicLong} 当日序号原子自增（参考 AccountServiceImpl 短信日限计数写法）。
 *
 * <p>docNo 规则（给 C2 复用）：{@code <prefix>-<simpleCode>-<yyyyMMdd>-<4位日内序号>}，例：{@code WK-T8801-20260629-0007}。
 * <ul>
 *   <li>prefix：{@link DocType#getPrefix()}（INBOUND→WK、OUTBOUND→CK、INQUIRY→XJ）。</li>
 *   <li>simpleCode：租户简码（不可读字符归一为 {@code T}+尾号占位）；用于分桶，避免跨租户序号互相影响。</li>
 *   <li>日内序号：Redis key {@code docno:<docType>:<simpleCode>:<yyyyMMdd>} INCR，从 1 起，当日 23:59:59 过期。</li>
 * </ul>
 * 单据表另设 doc_no 唯一索引兜底（G-5.1 双层防护）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentNumberServiceImpl implements DocumentNumberService {

    private final RedissonClient redissonClient;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Override
    public String generate(DocType docType, String tenantSimpleCode) {
        String simpleCode = normalize(tenantSimpleCode);
        String day = LocalDate.now(ZONE).format(DAY_FMT);
        String key = "docno:" + docType.name() + ":" + simpleCode + ":" + day;

        RAtomicLong seq = redissonClient.getAtomicLong(key);
        long n = seq.incrementAndGet();
        if (n == 1L) {
            // 首次设置当日剩余 TTL，序号每日重置
            seq.expire(java.time.Duration.ofSeconds(secondsUntilEndOfDay()));
        }

        String docNo = String.format("%s-%s-%s-%04d", docType.getPrefix(), simpleCode, day, n);
        log.debug("[DOC] generate docNo={} (docType={}, seq={})", docNo, docType, n);
        return docNo;
    }

    /** 简码归一：去空白 + 取后 6 位字母数字，空则占位 {@code T0000}，避免 key/编号含非法字符。 */
    private String normalize(String tenantSimpleCode) {
        if (tenantSimpleCode == null) {
            return "T0000";
        }
        String s = tenantSimpleCode.replaceAll("[^A-Za-z0-9]", "");
        if (s.isEmpty()) {
            return "T0000";
        }
        return s.length() > 6 ? s.substring(s.length() - 6) : s;
    }

    private long secondsUntilEndOfDay() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        LocalDateTime endOfDay = LocalDateTime.of(now.toLocalDate(), LocalTime.MAX);
        long secs = java.time.Duration.between(now, endOfDay).getSeconds();
        return Math.max(secs, 1);
    }
}
