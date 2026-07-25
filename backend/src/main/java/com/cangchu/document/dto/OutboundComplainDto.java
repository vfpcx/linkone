package com.cangchu.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 30 天客诉发起（P3 BE-W2，12 §3.4 / PRD 09 §3：仅 source=WK_CREATED 且已出库的单，WA 发起）。
 */
@Data
public class OutboundComplainDto {

    @NotBlank(message = "缺少客诉理由")
    @Size(max = 512, message = "客诉理由不能超过512字")
    private String reason;

    /** 附件 URL（≤5 个，走 POST /api/v1/files 上传） */
    @Size(max = 5, message = "附件最多5个")
    private List<String> attachments;
}
