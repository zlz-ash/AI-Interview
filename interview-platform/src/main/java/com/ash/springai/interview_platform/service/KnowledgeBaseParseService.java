package com.ash.springai.interview_platform.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseParseService {
    
    private final DocumentParseService documentParseService;
    private final ContentTypeDetectionService contentTypeDetectionService;
    private final FileStorageService storageService;

    public String parseContent(MultipartFile file){
        log.info("开始解析知识库文件:{}",file.getOriginalFilename());
        return documentParseService.parseContent(file);
    }

    public String parseContent(byte[] fileBytes,String filename){
        log.info("开始解析知识库文件:{}",filename);
        return documentParseService.parseContent(fileBytes,filename);
    }

    public String detectContentType(MultipartFile file){
        return contentTypeDetectionService.detectContentType(file);
    }

    public String downloadAndParseContent(String storageKey, String originalFilename) {
        log.info("从存储下载并解析知识库文件: {}", originalFilename);
        return documentParseService.downloadAndParseContent(storageService, storageKey, originalFilename);
    }
}
