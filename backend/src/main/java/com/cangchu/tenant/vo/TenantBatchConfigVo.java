package com.cangchu.tenant.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户批次配置只读视图（P3b T4-W1，13 §3.5）。
 * 供 document/inventory 编排域读取批次开关状态与推算参数（G-S1/G-S2：不跨域直连 TenantSettingsMapper）。
 */
@Data
@Builder
public class TenantBatchConfigVo {

    /** 1=批次功能已启用 */
    private Integer batchEnabled;

    /** 最近启用时刻（FIFO 推算的流水切割时点；从未启用为 null） */
    private LocalDateTime batchEnabledAt;

    /** 临期阈值天数（默认 30） */
    private Integer expiryThresholdDays;

    /** 货位功能开关（V40，C2 25-p5-c-c2 §3.1；默认 0=关闭；各端读开关显隐统一走本端点） */
    private Integer locationEnabled;
}
