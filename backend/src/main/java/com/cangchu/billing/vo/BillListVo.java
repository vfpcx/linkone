package com.cangchu.billing.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 账单列表 VO（P4 W3，14 §6.2）：分页记录 + 汇总卡（应收/已收/未收合计，按当前筛选）
 */
@Data
@Builder
public class BillListVo {

    private List<BillVo> records;

    private long total;

    private long page;

    private long size;

    /** 应收合计（当前筛选） */
    private BigDecimal receivable;

    /** 已收合计 */
    private BigDecimal received;

    /** 未收合计 */
    private BigDecimal outstanding;
}
