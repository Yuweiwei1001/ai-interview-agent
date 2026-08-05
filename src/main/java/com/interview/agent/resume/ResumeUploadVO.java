package com.interview.agent.resume;

import java.time.LocalDateTime;

public class ResumeUploadVO {
    private Long id;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String rawTextPreview;
    private LocalDateTime createdAt;

    public ResumeUploadVO() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getRawTextPreview() { return rawTextPreview; }
    public void setRawTextPreview(String rawTextPreview) { this.rawTextPreview = rawTextPreview; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}