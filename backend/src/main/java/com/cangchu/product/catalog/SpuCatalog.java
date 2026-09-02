package com.cangchu.product.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台标品两级品类预置字典（P5-D D56，16 §3 拍板清单，22 §3.4）。
 *
 * <p>两级品类以中文文本存 spus 表（零角色码精神，商品名无码场景）；本类为唯一事实源，
 * `/api/v1/ops/spu-categories` 出字典供 OPS 新增弹窗两级联动（前端下拉与后端校验同源）。
 * 后置：字典维护界面（16 §9），届时迁移为 DB 表不改本契约。
 */
public final class SpuCatalog {

    private SpuCatalog() {
    }

    /** 保序字典：L1 → 有序 L2 列表（16 §3 示例表全采纳）。 */
    public static final Map<String, List<String>> L1_L2S = new LinkedHashMap<>();

    static {
        L1_L2S.put("粮油调味", List.of("米面粮油", "食用油", "调味品", "干货杂粮"));
        L1_L2S.put("酒水饮料", List.of("饮用水", "碳酸/果汁", "茶咖", "啤酒白酒"));
        L1_L2S.put("休闲零食", List.of("饼干糕点", "糖果巧克力", "坚果炒货", "膨化食品"));
        L1_L2S.put("方便速食", List.of("方便面", "速冻食品", "罐头", "挂面粉丝"));
        L1_L2S.put("乳品冲调", List.of("牛奶", "酸奶", "奶粉", "冲调饮品"));
        L1_L2S.put("日化清洁", List.of("洗衣液/皂", "清洁剂", "纸品"));
        L1_L2S.put("个护美妆", List.of("洗护发", "沐浴", "护肤"));
        L1_L2S.put("家居百货", List.of("厨房用品", "收纳", "一次性用品"));
        L1_L2S.put("其他", List.of("其他"));
    }

    public static boolean validL2(String l1, String l2) {
        if (l1 == null || l2 == null) {
            return false;
        }
        List<String> l2s = L1_L2S.get(l1);
        return l2s != null && l2s.contains(l2);
    }
}
