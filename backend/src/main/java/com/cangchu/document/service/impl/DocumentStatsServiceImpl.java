package com.cangchu.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.document.dto.TodayCountsDto;
import com.cangchu.document.entity.InboundRequest;
import com.cangchu.document.entity.InquiryRequest;
import com.cangchu.document.entity.OutboundRequest;
import com.cangchu.document.mapper.InboundRequestMapper;
import com.cangchu.document.mapper.InquiryRequestMapper;
import com.cangchu.document.mapper.OutboundRequestMapper;
import com.cangchu.document.service.DocumentStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 单据统计出口实现（P5-C，19 §3）：域内直连三个单据 mapper，只读计数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentStatsServiceImpl implements DocumentStatsService {

    private final InboundRequestMapper inboundRequestMapper;
    private final OutboundRequestMapper outboundRequestMapper;
    private final InquiryRequestMapper inquiryRequestMapper;

    @Override
    public TodayCountsDto todayCounts(Long tenantId) {
        LocalDateTime from = LocalDate.now().atStartOfDay();
        long inbound = countInbound(tenantId, from);
        long outbound = countOutbound(tenantId, from);
        long inquiry = countInquiry(tenantId, from);
        return TodayCountsDto.builder()
                .inboundCount(inbound)
                .outboundCount(outbound)
                .inquiryCount(inquiry)
                .build();
    }

    private long countInbound(Long tenantId, LocalDateTime from) {
        Long cnt = inboundRequestMapper.selectCount(new LambdaQueryWrapper<InboundRequest>()
                .eq(InboundRequest::getTenantId, tenantId)
                .ge(InboundRequest::getCreatedAt, from));
        return cnt != null ? cnt : 0;
    }

    private long countOutbound(Long tenantId, LocalDateTime from) {
        Long cnt = outboundRequestMapper.selectCount(new LambdaQueryWrapper<OutboundRequest>()
                .eq(OutboundRequest::getTenantId, tenantId)
                .ge(OutboundRequest::getCreatedAt, from));
        return cnt != null ? cnt : 0;
    }

    private long countInquiry(Long tenantId, LocalDateTime from) {
        Long cnt = inquiryRequestMapper.selectCount(new LambdaQueryWrapper<InquiryRequest>()
                .eq(InquiryRequest::getTenantId, tenantId)
                .ge(InquiryRequest::getCreatedAt, from));
        return cnt != null ? cnt : 0;
    }
}
