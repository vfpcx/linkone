package com.cangchu.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * WA 入库异议入参（P3 BE-W1，12 §2.3）。
 * reason 必填 ≤512；attachments 选填 URL ≤5 个（先经 POST /api/v1/files 上传取回 URL）。
 */
@Data
public class InboundDisputeDto {

    @NotBlank(message = "异议理由不能为空")
    @Size(max = 512, message = "异议理由最长 512 字")
    private String reason;

    @Size(max = 5, message = "附件最多 5 张")
    private List<@Size(max = 200, message = "附件 URL 过长") String> attachments;
}
