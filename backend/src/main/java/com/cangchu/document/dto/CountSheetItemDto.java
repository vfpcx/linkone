package com.cangchu.document.dto;

import lombok.Data;

/**
 * 盘点明细行入参（P3b T3-W2，13 §5.2）。
 * actualQty 实物数 ≥0（&lt;0 → 50355）；palletDelta 可空 ≥0——盘盈行=占用 +M、
 * 盘亏行=释放数覆盖（含 0），NULL=盘亏默认比例建议值；remark 差异理由。
 */
@Data
public class CountSheetItemDto {
    private Long skuId;
    private Integer actualQty;
    private Integer palletDelta;
    /**
     * 盘盈按批登记（仅盘盈行可带）：批次号 ≤64；给了批次号 → expiryDate 必填。
     * 生产日期可选（现场不知可不录）；productionDate/expiryDate 为 ISO yyyy-MM-dd。
     */
    private String gainBatchNo;
    private java.time.LocalDate gainProductionDate;
    private java.time.LocalDate gainExpiryDate;
    private String remark;
}
