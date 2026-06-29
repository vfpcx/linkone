package com.cangchu.document.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

@Data
public class SubmitInquiryDto {

    private Long storeId;
    private String code;

    @NotNull(message = "批发商ID不能为空")
    private Long wholesalerId;

    @NotBlank(message = "RT手机号不能为空")
    private String rtPhone;

    @NotEmpty(message = "询价商品不能为空")
    private List<InquiryItemDto> items;

    @Data
    public static class InquiryItemDto {
        @NotNull(message = "SKU ID不能为空")
        private Long skuId;

        @NotNull(message = "数量不能为空")
        @Positive(message = "数量必须大于0")
        private Integer qty;
    }
}
