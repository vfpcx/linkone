package com.cangchu.document.dto;

import lombok.Data;

import java.util.List;

/**
 * 盘点单编辑入参（P3b T3-W2，13 §5.2：PUT /tenant/count-sheets/{id}，WK）。
 * 草稿可直接编辑；被驳回（REJECTED）编辑时先 CAS 回 DRAFT 重提（矩阵 REJECTED→DRAFT；
 * pending_flag 回置 1 撞同商户新在途单 → 50356）。items 全量替换语义；商户不可变更。
 */
@Data
public class CountSheetUpdateDto {
    private String remark;
    private List<String> attachments;
    private List<CountSheetItemDto> items;
}
