package com.cangchu.document.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class InquiryVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String docNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    private String status;

    private String rtPhone;

    private LocalDateTime createdAt;

    private LocalDateTime confirmedAt;

    private List<InquiryItemVo> items;

    @Data
    public static class InquiryItemVo {
        @JsonSerialize(using = ToStringSerializer.class)
        private Long id;

        @JsonSerialize(using = ToStringSerializer.class)
        private Long skuId;

        private Integer qty;

        private BigDecimal unitPriceSnapshot;

        private BigDecimal moqPriceSnapshot;

        private Integer moqQtySnapshot;

        private BigDecimal dealPrice;
    }
}
