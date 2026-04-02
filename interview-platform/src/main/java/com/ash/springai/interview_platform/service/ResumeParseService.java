package com.ash.springai.interview_platform.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeParseService {

    private final DocumentParseService documentParseService;
    private final ContentTypeDetectionService contentTypeDetectionService;
    private final FileStorageService storageService;

    public String parseResume(MultipartFile file) {
        log.info("开始解析简历文件: {}", file.getOriginalFilename());
        return documentParseService.parseContent(file);
    }

    public String parseResume(byte[] fileBytes, String fileName) {
        log.info("开始解析简历文件（从字节数组）: {}", fileName);
        return documentParseService.parseContent(fileBytes, fileName);
    }

    public String downloadAndParseContent(String storageKey, String originalFilename) {
        log.info("从存储下载并解析简历文件: {}", originalFilename);
        return documentParseService.downloadAndParseContent(storageService, storageKey, originalFilename);
    }

    public String detectContentType(MultipartFile file) {
        return contentTypeDetectionService.detectContentType(file);
    }

}
