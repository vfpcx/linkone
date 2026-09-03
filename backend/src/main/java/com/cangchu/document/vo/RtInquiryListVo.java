package com.cangchu.document.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * RT「我的意向单」响应（F2 · US-RT-04）。
 *
 * <p>仅回手机号尾号 4 位作归属提示（PII：永不返回明文）；wholesaler 名经 tenant 域
 * WholesalerService 出口、SKU 名经 product 域 SkuService 出口补全（G-S2）。
 */
@Data
@Builder
public class RtInquiryListVo {

    /** 归属提示：手机号尾号 4 位 */
    private String rtPhoneLast4;

    /** 店铺名（由 storeId/code 解析出的展示上下文） */
    private String storeName;

    /** 该手机号在本店的意向单（createdAt 倒序；无记录空数组，HTTP 200） */
    private List<Summary> inquiries;

    @Data
    @Builder
    public static class Summary {

        @JsonSerialize(using = ToStringSerializer.class)
        private Long inquiryId;

        private String docNo;

        /** PENDING=已提交 / CONFIRMED=批发商已确认 / COMPLETED=已完成 / VOIDED=已作废 */
        private String status;

        @JsonSerialize(using = ToStringSerializer.class)
        private Long wholesalerId;

        private String wholesalerName;

        private LocalDateTime createdAt;

        private LocalDateTime confirmedAt;

        private LocalDateTime voidedAt;

        private List<Item> items;
    }

    @Data
    @Builder
    public static class Item {

        @JsonSerialize(using = ToStringSerializer.class)
        private Long skuId;

        private String name;

        private String spec;

        private Integer qty;

        private BigDecimal unitPriceSnapshot;

        private BigDecimal moqPriceSnapshot;

        private Integer moqQtySnapshot;

        /** 成交价：WA 确认时可改写；未确认/未改写为 null */
        private BigDecimal dealPrice;
    }
}
