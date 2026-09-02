package com.cangchu.document.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 客户备注覆盖保存（C3 · 24-p5-c-c3 §4.3）：remark ≤200 覆盖式；空串=清除备注（K-3）。
 * wholesalerId 为归属收敛键（须属登录人 scope，否则 50840）。
 */
@Data
public class CustomerRemarkDto {

    @NotNull(message = "wholesalerId 不能为空")
    private Long wholesalerId;

    // 允许 null/空串 = 清除备注（K-3），仅限长度
    @Size(max = 200, message = "备注不能超过 200 字")
    private String remark;
}
