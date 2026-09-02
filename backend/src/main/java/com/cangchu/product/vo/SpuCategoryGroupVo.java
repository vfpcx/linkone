package com.cangchu.product.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 两级品类字典项（P5-D D56，22 §3.4）：{l1, l2s[]}，OPS 新增弹窗两级联动下拉同源。
 */
@Data
@Builder
public class SpuCategoryGroupVo {

    /** 一级品类（中文文本，预置字典） */
    private String l1;

    /** 该一级下的二级品类列表 */
    private List<String> l2s;
}
