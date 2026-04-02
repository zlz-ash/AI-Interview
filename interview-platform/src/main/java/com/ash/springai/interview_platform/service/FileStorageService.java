package com.ash.springai.interview_platform.service;

import org.springframework.web.multipart.MultipartFile;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import com.ash.springai.interview_platform.config.StorageConfigProperties;
import com.ash.springai.interview_platform.exception.BusinessException;
import com.ash.springai.interview_platform.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService{

    private final S3Client s3Client;
    private final StorageConfigProperties storageConfig;

    public String uploadResume(MultipartFile file){
        return uploadFile(file,"resumes");
    }

    public byte[] downloadResume(String fileKey){
        return downloadFile(fileKey);
    }

    public void deleteResume(String fileKey){
        deleteFile(fileKey);
    }

    public String uploadKnowledgeBase(MultipartFile file){
        return uploadFile(file,"knowledgebases");
    }

    public void deleteKnowledgeBase(String fileKey){
        deleteFile(fileKey);
    }

    private String uploadFile(MultipartFile file,String prefix){
        String originalFilename = file.getOriginalFilename();
        String fileKey = generateFileKey(originalFilename,prefix);

        try{
            PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(storageConfig.getBucket())
                .key(fileKey)
                .contentType(file.getContentType())
                .contentLength(file.getSize())
                .build();

            s3Client.putObject(putRequest,
                RequestBody.fromInputStream(file.getInputStream(),file.getSize())
            );

            log.info("文件上传成功： {} -> {}", originalFilename,fileKey);
            return fileKey;
        }catch(IOException e){
            log.error("读取文件上传失败: {}",e.getMessage(),e);
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED,"文件读取失败");
        }catch(S3Exception e){
            log.error("文件上传到rustfs失败: {}",e.getMessage(),e);
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED,"文件存储失败:" + e.getMessage());
        }
    }

    public byte[] downloadFile(String fileKey){
        if(!fileExists(fileKey)){
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED,"文件不存在："+fileKey);
        }

        try{
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(fileKey)
                    .build();
            return s3Client.getObjectAsBytes(getRequest).asByteArray();
        }catch(S3Exception e){
            log.error("文件下载失败：{} - {}",fileKey,e.getMessage(),e);
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED,"文件下载失败:"+e.getMessage());
        }
    }

    public boolean fileExists(String fileKey){
        try{
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(fileKey)
                    .build();
            s3Client.headObject(headRequest);
            return true;
        }catch(NoSuchKeyException e){
            return false;
        }catch (S3Exception e){
            log.warn("检查文件存在失败：{} - {}",fileKey,e.getMessage(),e);
            return false;
        }
    }

    private void deleteFile(String fileKey){
        if(fileKey == null || fileKey.isEmpty()){
            log.debug("文件键为空，跳过删除");
        }

        if(!fileExists(fileKey)){
            log.warn("文件不存在：{}",fileKey);
            return;
        }

        try{
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(fileKey)
                    .build();
            s3Client.deleteObject(deleteRequest);
            log.info("文件删除成功：{}",fileKey);
        }catch(S3Exception e){
            log.error("文件删除失败：{} - {}",fileKey,e.getMessage(),e);
            throw new BusinessException(ErrorCode.STORAGE_DELETE_FAILED,"文件删除失败：{}"+e.getMessage());
        }
    }

    private String generateFileKey(String originalFilename,String prefix){
        LocalDateTime now = LocalDateTime.now();
        String dataPath = now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String uuid = UUID.randomUUID().toString().substring(0,8);
        String safeName = sanitizerFilename(originalFilename);
        return String.format("%s/%s/%s_%s",prefix,dataPath,uuid,safeName);
    }

    private String sanitizerFilename(String filename){
        if(filename == null)
            return "unknown";
        return filename.replaceAll("[^a-zA-Z0-9._-]","_");
    }

    public void ensureBucketExists(){
        try{
            HeadBucketRequest headRequest = HeadBucketRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .build();
            s3Client.headBucket(headRequest);
            log.info("bucket已存在:{}",storageConfig.getBucket());
        }catch(NoSuchBucketException e){
            log.info("bucket不存在,正在创建：{}",storageConfig.getBucket());
            CreateBucketRequest createRequest = CreateBucketRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .build();
            s3Client.createBucket(createRequest);
            log.info("bucket创建成功:{}",storageConfig.getBucket());
        }catch(S3Exception e){
            log.error("bucket创建失败:{} - {}",storageConfig.getBucket(),e.getMessage(),e);
        }
    }

    public String getFileUrl(String fileKey) {
        return String.format("%s/%s/%s", storageConfig.getEndpoint(), storageConfig.getBucket(), fileKey);
    }

    
}