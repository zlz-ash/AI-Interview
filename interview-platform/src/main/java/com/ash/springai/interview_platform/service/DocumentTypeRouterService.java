package com.ash.springai.interview_platform.service;

import org.springframework.stereotype.Service;

import com.ash.springai.interview_platform.enums.DocumentType;

@Service
public class DocumentTypeRouterService {

    public DocumentType route(String contentType, String filename, String content) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return DocumentType.EXCEL_TABLE;
        }
        if (lower.endsWith(".pdf")) {
            return DocumentType.PDF_LONGFORM;
        }
        if (contentType != null && !contentType.isBlank()) {
            String ct = contentType.toLowerCase();
            if (ct.contains("pdf")) {
                return DocumentType.PDF_LONGFORM;
            }
            if (ct.contains("spreadsheetml") || ct.contains("ms-excel")) {
                return DocumentType.EXCEL_TABLE;
            }
        }
        return DocumentType.MARKDOWN_TEXT;
    }
}
