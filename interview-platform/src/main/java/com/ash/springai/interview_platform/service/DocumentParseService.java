package com.ash.springai.interview_platform.service;

import org.springframework.stereotype.Service;

import com.ash.springai.interview_platform.Tika.NoOpEmbeddedDocumentExtractor;
import com.ash.springai.interview_platform.exception.BusinessException;
import com.ash.springai.interview_platform.exception.ErrorCode;

import org.apache.tika.exception.TikaException;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.springframework.web.multipart.MultipartFile;

import org.xml.sax.SAXException;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;

@Slf4j
@Service
public class DocumentParseService {
    private static final int MAX_TEXT_LENGTH = 10*1024*1024;

    private final TextCleaningService textCleaningService;
    
    private String parseContent(InputStream inputStream)
            throws IOException,TikaException,SAXException{
        
        AutoDetectParser parser = new AutoDetectParser();

        BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_LENGTH);

        Metadata metadata = new Metadata();

        ParseContext context = new ParseContext();

        context.set(Parser.class,parser);

        context.set(EmbeddedDocumentExtractor.class,
            new NoOpEmbeddedDocumentExtractor()
        );

        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(false);
        context.set(PDFParserConfig.class,pdfConfig);

        parser.parse(inputStream,handler,metadata,context);

        return textCleaningService.cleanText(handler.toString());
    }

    public String parseContent(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        log.info("开始解析文件: {}", fileName);

        // 处理空文件
        if (file.isEmpty() || file.getSize() == 0) {
            log.warn("文件为空: {}", fileName);
            return "";
        }

        try (InputStream inputStream = file.getInputStream()) {
            String content = parseContent(inputStream);
            String cleanedContent = textCleaningService.cleanText(content);
            log.info("文件解析成功，提取文本长度: {} 字符", cleanedContent.length());
            return cleanedContent;
        } catch (IOException | TikaException | SAXException e) {
            log.error("文件解析失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析失败: " + e.getMessage());
        }
    }

    public String parseContent(byte[] fileBytes, String fileName) {
        log.info("开始解析文件（从字节数组）: {}", fileName);

        // 处理空文件
        if (fileBytes == null || fileBytes.length == 0) {
            log.warn("文件字节数组为空: {}", fileName);
            return "";
        }

        try (InputStream inputStream = new ByteArrayInputStream(fileBytes)) {
            String content = parseContent(inputStream);
            String cleanedContent = textCleaningService.cleanText(content);
            log.info("文件解析成功，提取文本长度: {} 字符", cleanedContent.length());
            return cleanedContent;
        } catch (IOException | TikaException | SAXException e) {
            log.error("文件解析失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析失败: " + e.getMessage());
        }
    }

    public String downloadAndParseContent(FileStorageService storageService, String storageKey, String originalFilename) {
        try {
            byte[] fileBytes = storageService.downloadFile(storageKey);
            if (fileBytes == null || fileBytes.length == 0) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载文件失败");
            }
            return parseContent(fileBytes, originalFilename);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("下载并解析文件失败: storageKey={}, error={}", storageKey, e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载并解析文件失败: " + e.getMessage());
        }
    }

    

    public DocumentParseService(TextCleaningService textCleaningService) {
        this.textCleaningService = textCleaningService;
    }
}
