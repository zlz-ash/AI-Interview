package com.ash.springai.interview_platform.initializer;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.ash.springai.interview_platform.service.FileStorageService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class StorageInitializer implements ApplicationRunner {
    
    private final FileStorageService storageService;

    @Override
    public void run(ApplicationArguments args){
        storageService.ensureBucketExists();
    }
}
