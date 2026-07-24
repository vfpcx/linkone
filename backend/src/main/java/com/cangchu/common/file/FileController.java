package com.cangchu.common.file;

import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.response.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 附件上传 Controller（P3 BE-W1，12 §4.4）。
 * POST /api/v1/files（登录用户，multipart 单文件，SaTokenConfig checkLogin 段已覆盖）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileStorageService fileStorageService;

    /** 上传图片附件（≤5MB，jpg/png/webp 魔数校验）→ {url}。 */
    @PostMapping
    public R<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.FILE_UPLOAD_INVALID);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.FILE_UPLOAD_INVALID);
        }
        return R.ok(Map.of("url", fileStorageService.store(bytes)));
    }
}
