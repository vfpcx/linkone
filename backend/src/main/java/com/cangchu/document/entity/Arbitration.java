package com.cangchu.document.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 仲裁单（P3 BE-W1，12 §4.1：双仲裁统一表，多态单据引用）。
 *
 * <p>INBOUND_DISPUTE（入库异议，TA 裁）本波启用；OUTBOUND_COMPLAINT（出库客诉，OPS 裁）BE-W2 启用。
 * 唯一性闸门=单据状态（单据只能进 DISPUTED/COMPLAINED 一次，天然一单一裁）。
 * 纳入 TenantLine 白名单；OPS 跨租户查询走无上下文先例。
 */
@Data
@TableName("arbitrations")
public class Arbitration {

    // ==================== biz_type ====================
    public static final String BIZ_INBOUND_DISPUTE = "INBOUND_DISPUTE";
    public static final String BIZ_OUTBOUND_COMPLAINT = "OUTBOUND_COMPLAINT";

    // ==================== status（两态最小，不可逆） ====================
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DECIDED = "DECIDED";

    // ==================== conclusion（取值域由 biz_type 决定，12 §4.2） ====================
    /** 入库：通过·恢复流水（异议不成立，入库有效） */
    public static final String CONCLUSION_APPROVED = "APPROVED";
    /** 入库：驳回·保留冲销（异议成立，入库无效） */
    public static final String CONCLUSION_REJECTED = "REJECTED";
    /** 出库客诉四选（BE-W2 启用；亦为 liability 差额定责取值域） */
    public static final String WK_LIABLE = "WK_LIABLE";
    public static final String WA_LIABLE = "WA_LIABLE";
    public static final String NEGOTIATED = "NEGOTIATED";
    public static final String NO_LIABILITY = "NO_LIABILITY";

    // ==================== ref_doc_type（DocType 名对齐） ====================
    public static final String REF_INBOUND = "INBOUND";
    public static final String REF_OUTBOUND = "OUTBOUND";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 仲裁单号（统一单据号体系：YY-/KS-，uk_arb_doc_no 唯一兜底） */
    private String docNo;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private String bizType;

    private String refDocType;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long refDocId;

    /** 冗余单据号（列表展示免 join，创建时快照） */
    private String refDocNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long initiatorUserId;

    /** 发起方角色（WA/WE） */
    private String initiatorRole;

    private String reason;

    /** JSON 数组，附件 URL ≤5 个 */
    private String attachments;

    /** 仅 INBOUND_DISPUTE：实际冲销件数（按在库封顶，落单后不可变） */
    private Integer reversedQty;

    /** 仅 INBOUND_DISPUTE：已售差额（定责输入，落单后不可变） */
    private Integer shortfallQty;

    private String status;

    private String conclusion;

    /** 差额定责（仅 INBOUND_DISPUTE∧REJECTED∧shortfall_qty>0 必填，其余必空，违规 50342） */
    private String liability;

    private String conclusionRemark;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long arbitratorUserId;

    private LocalDateTime decidedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
