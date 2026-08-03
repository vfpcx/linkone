package com.cangchu.inventory.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 批次列表视图（P3b T4-W1，13 §3.2-6）。
 * 指定 wholesalerId+skuId 下钻时附「无批次在池量」= inventories.qty − Σ非终态批次推算剩余
 * （盘盈未摊完/回补时序所致，独立行展示、不报警）；未指定到 SKU 粒度时为 null。
 */
@Data
@Builder
public class BatchListVo {

    private List<BatchVo> list;

    /** 无批次在池量（仅 SKU 下钻时计算；可为负——推算滞后窗口内属正常，UI 归 0 展示） */
    private Integer unpooledQty;
}
