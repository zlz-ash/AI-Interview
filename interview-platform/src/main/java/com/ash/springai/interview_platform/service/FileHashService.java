package com.ash.springai.interview_platform.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.InputStream;

import org.springframework.web.multipart.MultipartFile;

import lombok.extern.slf4j.Slf4j;

import com.ash.springai.interview_platform.exception.BusinessException;
import com.ash.springai.interview_platform.exception.ErrorCode;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class FileHashService {
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final int BUFFER_SIZE = 8192;

    public String calculateHash(MultipartFile file){
        try{
            return calculateHash(file.getBytes());
        }catch(IOException e){
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,"计算文件哈希失败");
        }
    }

    public String calculateHash(byte[] data){
        try{
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashBytes = digest.digest(data);
            return bytesToHex(hashBytes);
        }catch(NoSuchAlgorithmException e){
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,"哈希算法不支持");
        }
    }

    public String calculateHash(InputStream inputStream){
        try{
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while((bytesRead = inputStream.read(buffer)) !=-1){
                digest.update(buffer,0,bytesRead);
            }
            return bytesToHex(digest.digest());
        }catch(NoSuchAlgorithmException | IOException e){
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,"计算文件哈希失败");
        }
    }

    private String bytesToHex(byte[] bytes){
        StringBuilder result = new StringBuilder(bytes.length*2);
        for(byte b : bytes){
            result.append(String.format("%02x",b));
        } 
        return result.toString();
    }
}
