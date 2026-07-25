package com.cangchu.common.file;

import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 本地盘附件存储实现（P3 BE-W1，12 §4.4 最小方案）。
 *
 * <p>安全口径：
 * <ul>
 *   <li>大小 ≤5MB、非空；魔数（file signature）白名单 jpg/png/webp——不信任 Content-Type 与文件名。</li>
 *   <li>落盘名 = UUID + 魔数推导扩展名，杜绝路径穿越/双扩展名投毒。</li>
 *   <li>GET /files/** 放行（URL 含 UUID 不可枚举，试点可接受；P5 换 OSS 签名 URL）。</li>
 * </ul>
 */
@Slf4j
@Service
public class FileStorageServiceImpl implements FileStorageService {

    static final long MAX_SIZE_BYTES = 5L * 1024 * 1024;

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    @Value("${app.upload-dir:./data/uploads}")
    private String uploadDir;

    @Override
    public String store(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_SIZE_BYTES) {
            throw new BizException(ErrorCode.FILE_UPLOAD_INVALID);
        }
        String ext = detectImageExt(bytes);
        if (ext == null) {
            throw new BizException(ErrorCode.FILE_UPLOAD_INVALID);
        }
        String month = LocalDate.now().format(MONTH_FMT);
        String fileName = UUID.randomUUID() + "." + ext;
        Path dir = Path.of(uploadDir, month);
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(fileName), bytes);
        } catch (IOException e) {
            log.error("[FILE] 附件落盘失败 dir={}", dir, e);
            throw new BizException(ErrorCode.SYSTEM_INTERNAL_001);
        }
        return "/files/" + month + "/" + fileName;
    }

    /** 魔数白名单：jpg(FFD8FF) / png(89504E470D0A1A0A) / webp(RIFF....WEBP)；不匹配返回 null。 */
    static String detectImageExt(byte[] b) {
        if (b.length >= 3
                && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (b.length >= 8
                && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && (b[4] & 0xFF) == 0x0D && (b[5] & 0xFF) == 0x0A && (b[6] & 0xFF) == 0x1A && (b[7] & 0xFF) == 0x0A) {
            return "png";
        }
        if (b.length >= 12
                && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }
}
