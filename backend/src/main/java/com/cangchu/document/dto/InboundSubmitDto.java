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

    /**
     * 提交附件 ≤5（T1-FE 移交补齐：随单举证照片，复用 /files，N2 白名单校验落申请单
     * attachments 列——本批拆出的每张单均落同一组；登记时 WK 上传登记照片会覆写）。
     */
    @Size(max = 5, message = "附件最多 5 张")
    private List<@Size(max = 200, message = "附件 URL 过长") String> attachments;

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

        // ==================== P3b T4-W1 批次三字段（租户批次开关启用时必填，13 §3.1） ====================

        /** 批次号（开关启用必填 ≤64；(商户,SKU,批次号) 唯一，重复 50362） */
        @Size(max = 64, message = "批次号最长 64 字")
        private String batchNo;

        /** 生产日期（开关启用必填；≤今天，40205） */
        private java.time.LocalDate productionDate;

        /** 到效期（开关启用必填；>生产日期，40206） */
        private java.time.LocalDate expiryDate;
    }
}
