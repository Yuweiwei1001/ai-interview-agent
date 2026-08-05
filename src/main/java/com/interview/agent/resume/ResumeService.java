package com.interview.agent.resume;

import com.interview.agent.common.context.BaseContext;
import com.interview.agent.common.exception.BaseException;
import com.interview.agent.resume.parser.TikaTextParser;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;

@Service
public class ResumeService {
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "text/plain"
    );

    private final ResumeMapper resumeMapper;
    private final TikaTextParser textParser;

    public ResumeService(ResumeMapper resumeMapper, TikaTextParser textParser) {
        this.resumeMapper = resumeMapper;
        this.textParser = textParser;
    }

    public ResumeUploadVO upload(MultipartFile file) {
        if (file.isEmpty()) throw new BaseException("文件不能为空");
        if (file.getSize() > MAX_FILE_SIZE) throw new BaseException("文件大小超过限制（10MB）");

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new BaseException("不支持的文件类型，仅支持 PDF/DOCX/DOC/TXT");
        }

        try (InputStream in = file.getInputStream()) {
            String rawText = textParser.parse(in, file.getOriginalFilename());
            if (rawText.isBlank()) throw new BaseException("无法解析简历内容");

            String contentHash = sha256(rawText);
            Long userId = BaseContext.getCurrentId();

            Resume resume = new Resume();
            resume.setUserId(userId);
            resume.setFileName(file.getOriginalFilename());
            resume.setFileType(contentType);
            resume.setFileSize(file.getSize());
            resume.setRawText(rawText);
            resume.setContentHash(contentHash);
            resumeMapper.insert(resume);

            ResumeUploadVO vo = new ResumeUploadVO();
            vo.setId(resume.getId());
            vo.setFileName(resume.getFileName());
            vo.setFileType(resume.getFileType());
            vo.setFileSize(resume.getFileSize());
            vo.setRawTextPreview(rawText.substring(0, Math.min(200, rawText.length())));
            vo.setCreatedAt(resume.getCreatedAt());
            return vo;
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            throw new BaseException("简历解析失败：" + e.getMessage());
        }
    }

    public List<Resume> list() {
        Long userId = BaseContext.getCurrentId();
        return resumeMapper.findByUserId(userId);
    }

    public Resume getById(Long id) {
        Resume resume = resumeMapper.findById(id);
        if (resume == null) throw new BaseException("简历不存在");
        if (!resume.getUserId().equals(BaseContext.getCurrentId())) {
            throw new BaseException("无权访问该简历");
        }
        return resume;
    }

    public void delete(Long id) {
        Long userId = BaseContext.getCurrentId();
        int affected = resumeMapper.deleteByIdAndUserId(id, userId);
        if (affected == 0) throw new BaseException("简历不存在或无权删除");
    }

    private String sha256(String text) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}