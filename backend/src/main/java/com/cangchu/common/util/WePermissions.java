package com.cangchu.common.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * WE 批发商员工授权位（P2 入驻 Wave3，决策 O-4 最小授权集）。
 *
 * <p>值域固定白名单两枚：{@link #PRICE_EDIT}（公开价/专属价编辑）、
 * {@link #INQUIRY_CONFIRM}（询价确认）。存储为 JSON 数组文本
 * （如 {@code ["PRICE_EDIT","INQUIRY_CONFIRM"]}，见 V12 迁移注释）。
 *
 * <p>编解码为手写极简实现而非 Jackson：值域是固定的大写标识符白名单，
 * 不存在转义/嵌套问题；解码对白名单外内容一律忽略（防脏数据放大权限）。
 * 跨 account（user_roles）/tenant（invite_codes）两域使用，故置于 common。
 */
public final class WePermissions {

    /** 改价（公开价走 pricing 批量端点 SET_VALUE，专属价 CRUD/批量） */
    public static final String PRICE_EDIT = "PRICE_EDIT";
    /** 询价确认（确认即原子转出库） */
    public static final String INQUIRY_CONFIRM = "INQUIRY_CONFIRM";
    /** 代建入库确认/异议（P3 BE-W1，G7 授权位扩 1 枚） */
    public static final String INBOUND_CONFIRM = "INBOUND_CONFIRM";

    public static final Set<String> ALLOWED = Set.of(PRICE_EDIT, INQUIRY_CONFIRM, INBOUND_CONFIRM);

    private WePermissions() {
    }

    /** 是否全部落在白名单内（null/空列表视为合法=无授权） */
    public static boolean allAllowed(List<String> permissions) {
        return permissions == null || permissions.stream().allMatch(p -> p != null && ALLOWED.contains(p.trim()));
    }

    /** 编码为 JSON 数组文本；null/空 → "[]"。入参须已过 allAllowed 校验。 */
    public static String encode(List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return "[]";
        }
        // 去重保序，白名单值无需转义
        Set<String> dedup = new LinkedHashSet<>();
        for (String p : permissions) {
            if (p != null && ALLOWED.contains(p.trim())) {
                dedup.add(p.trim());
            }
        }
        StringBuilder sb = new StringBuilder("[");
        for (String p : dedup) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append('"').append(p).append('"');
        }
        return sb.append(']').toString();
    }

    /** 解码 JSON 数组文本 → 授权列表；null/空/畸形 → 空列表；白名单外条目丢弃。 */
    public static List<String> decode(String json) {
        List<String> out = new ArrayList<>(2);
        if (json == null || json.isBlank()) {
            return out;
        }
        for (String token : json.replace("[", "").replace("]", "").replace("\"", "").split(",")) {
            String p = token.trim();
            if (ALLOWED.contains(p) && !out.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** 授权判定：JSON 文本中是否含指定授权位。 */
    public static boolean has(String json, String permission) {
        return decode(json).contains(permission);
    }
}
