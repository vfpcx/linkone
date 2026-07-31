package com.cangchu.document.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * WA/WE 提交入库申请入参（P3b T1-BE，13 §5.1）。
 *
 * <p>D-5 多 SKU：沿一单一 SKU——表单多行 → 后端单事务拆 N 张单（共享 batch_submit_id
 * 雪花，同批打印聚合），无明细表。tenantId 由 wholesaler 真实归属推导（S4，不取客户端）。
 */
@Data
public class InboundSubmitDto {

    /** 目标批发商商户（必填；提交人须为该商户 WA 或持 INBOUND_SUBMIT 的 WE） */
    @NotNull(message = "缺少批发商商户")
    private Long wholesalerId;

    /** 申请明细行（≥1；每行拆一张单） */
    @NotEmpty(message = "申请明细不能为空")
    @Size(max = 50, message = "单次最多提交 50 行")
    @Valid
    private List<Item> items;

    @Data
    public static class Item {

        @NotNull(message = "缺少商品 SKU")
        private Long skuId;

        /** 申请件数（>0；落 requested_qty，登记前不可变） */
        @NotNull(message = "缺少申请件数")
        @Min(value = 1, message = "申请件数必须大于0")
        private Integer qty;

        /** 预计托盘数（可空，默认 0；登记时 WK 可覆写） */
        @Min(value = 0, message = "托盘数不能为负")
        private Integer palletQty;

        /** 行备注（选填 ≤512） */
        @Size(max = 512, message = "备注最长 512 字")
        private String remark;
    }
}
