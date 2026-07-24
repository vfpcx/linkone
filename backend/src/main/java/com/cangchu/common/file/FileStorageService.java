package com.cangchu.common.file;

/**
 * 附件上传最小基建（P3 BE-W1，12 §4.4：异议/客诉附件共用，后续入库照片可复用）。
 *
 * <p>演进债（12 §8.5 显式登记）：本地盘存储、无副本、无签名 URL；P5 换 OSS 时
 * 返回的 URL 字段结构不变（前端无感）。
 */
public interface FileStorageService {

    /**
     * 保存图片附件：≤5MB；魔数校验仅 jpg/png/webp（扩展名由魔数推导，不信任文件名）；
     * 落盘 {@code ${app.upload-dir}/{yyyyMM}/{uuid}.{ext}}。
     *
     * @param bytes 文件字节
     * @return 可访问 URL（/files/{yyyyMM}/{uuid}.{ext}，静态映射 + UUID 不可枚举）
     * @throws com.cangchu.common.exception.BizException FILE_UPLOAD_INVALID(50340) 尺寸/魔数不符
     */
    String store(byte[] bytes);
}
