package com.cangchu.document.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * WK 登记正向链入库申请入参（P3b T1-BE，13 §1.2/§5.1）。
 *
 * <p>5% 差异边界：|actualQty − requested_qty| / requested_qty ≤ 5%（含等于）按实登记，
 * 差异非 0 时 remark 必填；>5% 抛 50351 提示走 R2 驳回。此刻才 addStock（登记才加库存）。
 */
@Data
public class InboundForwardRegisterDto {

    /** 实登件数（>0） */
    @NotNull(message = "缺少实登件数")
    @Min(value = 1, message = "实登件数必须大于0")
    private Integer actualQty;

    /** 实际托盘数（可空 → 沿用提交值；≥0） */
    @Min(value = 0, message = "托盘数不能为负")
    private Integer palletQty;

    /** 差异备注（实登 ≠ 申请件数时必填 ≤512） */
    @Size(max = 512, message = "备注最长 512 字")
    private String remark;

    /** 登记照片 ≤5（D-2 最小版，复用 /files，N2 白名单） */
    @Size(max = 5, message = "附件最多 5 张")
    private List<@Size(max = 200, message = "附件 URL 过长") String> attachments;

    /**
     * 过期批次强警告二次确认凭据（P3b T4-W1，13 §3.1）：单据批次到效期 ≤ 今天时登记必须
     * 显式传 true（缺失抛 50364）；临期仅前端黄条警告放行，无需凭据。
     */
    private Boolean expiredConfirmed;

    // ==================== P5-D C2 货位（25-p5-c-c2 §4.2：登记时按当刻 locationEnabled 校验必填 50822） ====================

    /** 货位号（货位开关启用时必填 ≤64；登记时填，落 inbound_requests.location，单据有批次号时同步 batches.location） */
    @Size(max = 64, message = "货位号最长 64 字")
    private String location;
}
