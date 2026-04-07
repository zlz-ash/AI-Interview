package com.ash.springai.interview_platform.service;

import com.ash.springai.interview_platform.Entity.IngestChunkDTO;
import com.ash.springai.interview_platform.config.IngestProperties;
import com.ash.springai.interview_platform.enums.DocumentType;
import com.ash.springai.interview_platform.service.strategy.ExcelChunkSplitter;
import com.ash.springai.interview_platform.service.strategy.MarkdownChunkSplitter;
import com.ash.springai.interview_platform.service.strategy.PdfChunkSplitter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSplitServiceTests {

    @Test
    void shouldRouteExcelToExcelSplitter() {
        DocumentTypeRouterService router = new DocumentTypeRouterService();
        DocumentType type = router.route(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "demo.xlsx",
            "header,a,b"
        );
        assertEquals(DocumentType.EXCEL_TABLE, type);
    }

    @Test
    void shouldDelegateMarkdownPdfAndExcel() {
        IngestProperties props = new IngestProperties();
        ChunkSplitService service = new ChunkSplitService(
            new MarkdownChunkSplitter(props),
            new ExcelChunkSplitter(props),
            new PdfChunkSplitter(props)
        );
        assertFalse(service.split(DocumentType.MARKDOWN_TEXT, "# T\n\nbody").isEmpty());
        assertFalse(service.split(DocumentType.PDF_LONGFORM, "Para one.\n\nPara two.").isEmpty());
        assertFalse(service.split(DocumentType.EXCEL_TABLE, "a,b\n1,2").isEmpty());
    }

    @Test
    void shouldUseConfiguredChunkTargetForMarkdownSplitter() {
        IngestProperties props = new IngestProperties();
        props.getMarkdown().setTargetMaxTokens(550);
        MarkdownChunkSplitter splitter = new MarkdownChunkSplitter(props);
        List<IngestChunkDTO> chunks = splitter.split("## A\n" + "x ".repeat(1400));
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(c -> c.tokenEstimate() <= 560));
    }
}
