package com.ash.springai.interview_platform.Tika;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;

import org.xml.sax.ContentHandler;

@Slf4j
public class NoOpEmbeddedDocumentExtractor implements EmbeddedDocumentExtractor{
    
    @Override
    public boolean shouldParseEmbedded(Metadata metadata){
        String resourceName = metadata.get("resourceName");
        if(resourceName != null){
            log.debug("Skip embedded document:{}",resourceName);
        }
        return false;
    }

    @Override
    public void parseEmbedded(InputStream inputStream,ContentHandler handler,
            Metadata metadata,boolean outputHtml)
    {}
}
