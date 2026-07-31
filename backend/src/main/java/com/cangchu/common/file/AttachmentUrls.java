package com.cangchu.common.file;

import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 单据附件 URL 编解码（P3b T1-BE 抽取，N2 白名单口径与 ArbitrationServiceImpl 同构）。
 *
 * <p>N2（08-p3-review）：附件 URL 必须是本站上传返回的形态
 * {@code /files/yyyyMM/UUID.(jpg|png|webp)}（与 {@link FileStorageServiceImpl#store} 返回值同构）。
 * 拒任意外链：防审批人浏览器向发起方指定外站发请求（IP/UA 泄露、跟踪像素），
 * 且外链「证据」可在裁决后被替换，破坏留痕语义。
 */
public final class AttachmentUrls {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern ATTACHMENT_URL_PATTERN = Pattern.compile(
            "^/files/\\d{6}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)$");

    private AttachmentUrls() {
    }

    /**
     * 校验（N2 白名单）+ 编码为 JSON 数组文本；null/空 → null（列不落值）。
     *
     * @throws BizException FILE_UPLOAD_INVALID(50340) 非本站上传地址；40001 超列宽/格式非法
     */
    public static String encode(List<String> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        for (String url : attachments) {
            if (url == null || !ATTACHMENT_URL_PATTERN.matcher(url).matches()) {
                throw new BizException(ErrorCode.FILE_UPLOAD_INVALID, "附件必须为本站上传返回的地址");
            }
        }
        try {
            String json = MAPPER.writeValueAsString(attachments);
            if (json.length() > 1024) {
                // 列宽 1024 兜底（≤5 个 ≤200 字 URL 正常不会触发）
                throw new BizException(ErrorCode.VALIDATION_BASIC_001, "附件 URL 过长");
            }
            return json;
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "附件格式非法");
        }
    }

    /** 解码 JSON 数组文本 → URL 列表；null/空/畸形 → 空列表（脏数据不阻断展示）。 */
    public static List<String> decode(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
