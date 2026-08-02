package com.cangchu.document.dto;

import lombok.Data;

import java.util.List;

/**
 * 盘点单建草稿入参（P3b T3-W2，13 §5.2：POST /tenant/count-sheets，WK）。
 * 一张盘点单盘一个商户；items 一单多 SKU（空/重复 SKU/实物数&lt;0 → 50355）；
 * attachments 现场照片 ≤5（N2 白名单）；同商户在途盘点单已存在 → 50356。
 */
@Data
public class CountSheetCreateDto {
    private Long wholesalerId;
    private String remark;
    private List<String> attachments;
    private List<CountSheetItemDto> items;
}
