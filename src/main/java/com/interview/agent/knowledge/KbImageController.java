package com.interview.agent.knowledge;

import com.interview.agent.common.exception.BaseException;
import com.interview.agent.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 知识库图片上传：文档编辑时粘贴/上传的图片持久化到本地磁盘，
 * 通过静态资源映射 /api/kb-images/** 访问（已在 WebMvcConfig 中放行 JWT，
 * 因为 &lt;img&gt; 标签无法携带 Authorization header）。
 */
@RestController
@RequestMapping("/api/kb-images")
public class KbImageController {

    private static final Logger log = LoggerFactory.getLogger(KbImageController.class);

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg");

    private final Path imageDir;

    public KbImageController(@Value("${kb.image-dir:kb-images}") String imageDir) {
        this.imageDir = Paths.get(imageDir).toAbsolutePath().normalize();
    }

    @PostMapping
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) throw new BaseException("图片不能为空");
        if (file.getSize() > MAX_FILE_SIZE) throw new BaseException("图片大小超过限制（5MB）");

        String ext = extensionOf(file.getOriginalFilename());
        if (ext == null || !ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BaseException("不支持的图片格式，仅支持 png/jpg/jpeg/gif/webp/bmp/svg");
        }

        try {
            Files.createDirectories(imageDir);
            String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            file.transferTo(imageDir.resolve(fileName).toFile());
            log.info("知识库图片上传成功: {}, size={}KB", fileName, file.getSize() / 1024);
            return Result.success(Map.of("url", "/api/kb-images/" + fileName));
        } catch (IOException e) {
            log.error("知识库图片保存失败", e);
            throw new BaseException("图片保存失败：" + e.getMessage());
        }
    }

    private String extensionOf(String filename) {
        if (filename == null || !filename.contains(".")) return null;
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return ext.isBlank() ? null : ext;
    }
}
