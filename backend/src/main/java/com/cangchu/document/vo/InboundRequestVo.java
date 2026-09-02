package com.cangchu.document.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 入库单出参（phase-1 C1）。
 */
@Data
@Builder
public class InboundRequestVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String docNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    private Integer qty;

    private Integer palletQty;

    private String status;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wkUserId;

    /** 登记后该 sku 最新库存（便于前端回显） */
    private Integer currentStock;

    // ==================== P3 BE-W1（12 §2）确认链字段 ====================

    /** 来源：WK_CREATED / WA_SUBMIT */
    private String source;

    /** 72h 确认截止（WA 队列按此升序倒计时） */
    private LocalDateTime waConfirmDeadline;

    private LocalDateTime waConfirmAt;

    /** 1=72h 超时自动确认 */
    private Integer autoAccepted;

    private LocalDateTime disputedAt;

    private LocalDateTime createdAt;

    // ==================== P3b T1（13 §1）正向申请链字段 ====================

    /** 申请件数（正向链；qty=实登件数，登记前二者相等） */
    private Integer requestedQty;

    /** R2 驳回原因单选：QTY/QUALITY/BATCH/OTHER */
    private String rejectReason;

    private String rejectRemark;

    /** R2 驳回举证附件 URL 列表 */
    private java.util.List<String> rejectAttachments;

    /** R1 撤回理由 */
    private String withdrawReason;

    /** 登记照片 URL 列表 */
    private java.util.List<String> attachments;

    private LocalDateTime printedAt;

    private Integer printCount;

    /** 登记时刻（R3 24h 窗口锚点） */
    private LocalDateTime registeredAt;

    /** 同批提交共享 id（多行拆单打印聚合） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long batchSubmitId;

    /** 提交人（WA 或被授权 WE） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long waUserId;

    /** 备注（提交行备注 / 登记差异备注） */
    private String remark;

    // ==================== P3b T4-W1 批次三字段（批次开关启用时落值） ====================

    private String batchNo;
    private java.time.LocalDate productionDate;
    private java.time.LocalDate expiryDate;

    // ==================== P5-D C2 货位（V40，25-p5-c-c2 §4.5：登记单展示） ====================

    /** 登记货位（未填为 null；wa/ta 列表与详情展示） */
    private String location;
}
