package com.cangchu.inventory.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * TA 批次开关入参（P3b T4-W1，13 §3.5：POST /api/v1/tenant/settings/batch-toggle）。
 * 副作用大（关→启生成默认批次 / 启→关冻结登记簿），不混在通用店铺设置接口里。
 */
@Data
public class BatchToggleDto {

    /** true=开启 / false=关闭 */
    @NotNull(message = "缺少开关目标状态")
    private Boolean enable;

    /** 弹窗二次确认凭据（状态实际翻转时必须为 true，否则 40003） */
    private Boolean confirmed;
}
