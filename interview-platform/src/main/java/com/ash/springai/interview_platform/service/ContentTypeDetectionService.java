package com.ash.springai.interview_platform.service;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.IOException;

import org.apache.tika.Tika;

@Service
@Slf4j
public class ContentTypeDetectionService {
    
    private final Tika tika;

    public ContentTypeDetectionService(){
        this.tika = new Tika();
    }

    public String detectContentType(MultipartFile file){
        try(InputStream inputStream = file.getInputStream()){
            return tika.detect(inputStream);
        }catch(IOException e){
            log.error("无法检测文件类型,使用Content-Type头部:{}",e.getMessage());
            return file.getContentType();
        }
    }

    public String detectContentType(InputStream inputStream,String filename){
        try{
            return tika.detect(inputStream,filename);
        }catch(IOException e){
            log.error("无法检测文件类型:{}",e.getMessage());
            return "application/octet-stream";
        }
    }

    public String detectContentType(byte[] data,String filename){
        return tika.detect(data,filename);
    }

    public boolean isPDF(String contentType){
        return contentType != null&& contentType.toLowerCase().contains("pdf");
    }

    public boolean isWordDocument(String contentType){
        if(contentType == null)return false;
        String lower = contentType.toLowerCase();
        return lower.contains("msword") || lower.contains("wordprocessingml");
    }

    public boolean isPlainText(String contentType){
        return contentType != null&& contentType.toLowerCase().startsWith("text/");
    }

    public boolean isMarkdown(String contentType,String filename){
        if(contentType != null){
            String lower = contentType.toLowerCase();
            if(lower.contains("markdown") || lower.contains("x-markdown")){
                return true;
            }
        }
        if(filename != null){
            String lowername = filename.toLowerCase();
            return lowername.endsWith(".md") || lowername.endsWith(".markdown")||lowername.endsWith(".mdown");
        }
        return false;
    }
}
