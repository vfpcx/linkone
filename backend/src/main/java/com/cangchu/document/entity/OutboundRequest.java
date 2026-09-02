package com.cangchu.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 出库单（P3 BE-W2 状态补拆，12 §1.2/§3）。
 *
 * <p>库存语义（拍板二 B，12 §1.3）：单据「创建成非终撤状态」瞬间库存已扣——
 * INQUIRY_AUTO=询价确认瞬间 / WA_SUBMIT=WA 提交瞬间（起点均 PENDING_ACCEPT）；
 * WK_CREATED=代建提交瞬间（直达 COMPLETED）。任何 WITHDRAWN/CANCELLED 迁移必配
 * OUTBOUND_REVERSAL 回补流水；其余迁移为纯作业/争议记录，不动库存。
 * 迁移矩阵见 {@link com.cangchu.document.statemachine.DocStateMachine}。
 */
@Data
@TableName("outbound_requests")
public class OutboundRequest {

    // ==================== 状态（12 §1.2；存量 COMPLETED 语义不变=已出库） ====================
    /** 待受理（询价确认/WA 提交生成，库存已扣） */
    public static final String STATUS_PENDING_ACCEPT = "PENDING_ACCEPT";
    /** 已打印（WK 打印，printed_at + print_count） */
    public static final String STATUS_PRINTED = "PRINTED";
    /** 已出库（WK 登记出库；代建直达） */
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** 已撤回（R4：待受理时 WA 直撤，终态，已回补） */
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";
    /** 已撤销（R4：已打印+WK 二次确认；R8：意向单作废联动。终态，已回补） */
    public static final String STATUS_CANCELLED = "CANCELLED";
    /** 客诉中（WA 对代建出库 30 天内客诉，OPS 裁决后回 COMPLETED） */
    public static final String STATUS_COMPLAINED = "COMPLAINED";

    // ==================== 来源（12 §1.3） ====================
    /** 询价确认自动生成（P1 现状） */
    public static final String SOURCE_INQUIRY_AUTO = "INQUIRY_AUTO";
    /** WA 手动出库申请（提交即扣） */
    public static final String SOURCE_WA_SUBMIT = "WA_SUBMIT";
    /** WK 代建出库（直达 COMPLETED） */
    public static final String SOURCE_WK_CREATED = "WK_CREATED";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String docNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long inquiryId;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    private Integer qty;

    private String status;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wkUserId;

    /** 来源（V18）：INQUIRY_AUTO / WA_SUBMIT / WK_CREATED */
    private String source;

    /** 首打时间（V18，补打不覆盖） */
    private LocalDateTime printedAt;

    /** 累计打印次数（V18，补打 count++ 不迁移状态） */
    private Integer printCount;

    /** 实际出库登记时刻（V18，30 天客诉窗口锚点） */
    private LocalDateTime completedAt;

    /** R4 撤回申请 flag（V18：已打印单需 WK 二次确认，1=待确认） */
    private Integer withdrawRequested;

    /** R4 撤回申请时刻（V18） */
    private LocalDateTime withdrawRequestedAt;

    /** 出库托盘数（V18，回补按此还原，默认 0） */
    private Integer palletQty;

    /** 拣出货位（V40，C2 25-p5-c-c2 §3.1：登记出库/代建时落单留痕；货位开关启用时必填 50822；零记账副作用） */
    private String location;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
