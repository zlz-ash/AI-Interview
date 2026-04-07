package com.ash.springai.interview_platform.service;

import com.ash.springai.interview_platform.Entity.KnowledgeBaseEntity;
import com.ash.springai.interview_platform.enums.DocumentType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentTypeRouterServiceTests {

    private final DocumentTypeRouterService router = new DocumentTypeRouterService();

    @Test
    void shouldPersistDocumentTypeAndIngestVersion() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setDocumentType(DocumentType.EXCEL_TABLE);
        kb.setIngestVersion("v2");
        kb.setIngestStatus("PENDING");
        assertEquals(DocumentType.EXCEL_TABLE, kb.getDocumentType());
        assertEquals("v2", kb.getIngestVersion());
        assertEquals("PENDING", kb.getIngestStatus());
    }

    @Test
    void shouldRouteExcelToExcelSplitter() {
        DocumentType type = router.route(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "demo.xlsx",
            "header,a,b"
        );
        assertEquals(DocumentType.EXCEL_TABLE, type);
    }

    @Test
    void shouldRoutePdfByMimeWhenExtensionMissing() {
        DocumentType type = router.route("application/pdf", "upload", "binary");
        assertEquals(DocumentType.PDF_LONGFORM, type);
    }

    @Test
    void shouldRouteExcelByMimeWhenExtensionMissing() {
        DocumentType type = router.route(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "upload",
            "x"
        );
        assertEquals(DocumentType.EXCEL_TABLE, type);
    }
}
