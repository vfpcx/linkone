package com.cangchu.inventory.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 批次开关操作结果（P3b T4-W1）。 */
@Data
@Builder
public class BatchToggleVo {

    /** 操作后开关状态 */
    private Integer batchEnabled;

    /** 最近启用时刻（FIFO 切割时点） */
    private LocalDateTime batchEnabledAt;

    /** 本次生成的默认批次数（关→启吸收存量；其余场景 0） */
    private Integer defaultBatchCount;

    /** 本次冻结的批次数（启→关；其余场景 0） */
    private Integer closedBatchCount;
}
