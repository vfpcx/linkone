package com.cangchu.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * R2 驳回入库申请入参（P3b T1-BE，13 §5.1）。
 * reason 单选（QTY/QUALITY/BATCH/OTHER）+ remark 必填 + 举证附件可选 ≤5（N2 白名单）。
 */
@Data
public class InboundRejectDto {

    /** 驳回原因单选：QTY(数量)/QUALITY(质量)/BATCH(批次不符)/OTHER(其他) */
    @NotBlank(message = "驳回原因不能为空")
    private String reason;

    @NotBlank(message = "驳回备注不能为空")
    @Size(max = 512, message = "驳回备注最长 512 字")
    private String remark;

    @Size(max = 5, message = "附件最多 5 张")
    private List<@Size(max = 200, message = "附件 URL 过长") String> attachments;
}
