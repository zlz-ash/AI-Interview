package com.ash.springai.interview_platform.service;

import org.springframework.stereotype.Service;

import com.ash.springai.interview_platform.Entity.IngestChunkDTO;
import com.ash.springai.interview_platform.enums.DocumentType;
import com.ash.springai.interview_platform.service.strategy.ExcelChunkSplitter;
import com.ash.springai.interview_platform.service.strategy.MarkdownChunkSplitter;
import com.ash.springai.interview_platform.service.strategy.PdfChunkSplitter;

import java.util.List;

@Service
public class ChunkSplitService {

    private final MarkdownChunkSplitter markdownSplitter;
    private final ExcelChunkSplitter excelSplitter;
    private final PdfChunkSplitter pdfSplitter;

    public ChunkSplitService(
        MarkdownChunkSplitter markdownSplitter,
        ExcelChunkSplitter excelSplitter,
        PdfChunkSplitter pdfSplitter
    ) {
        this.markdownSplitter = markdownSplitter;
        this.excelSplitter = excelSplitter;
        this.pdfSplitter = pdfSplitter;
    }

    public List<IngestChunkDTO> split(DocumentType type, String content) {
        return switch (type) {
            case EXCEL_TABLE -> excelSplitter.split(content);
            case PDF_LONGFORM -> pdfSplitter.split(content);
            default -> markdownSplitter.split(content);
        };
    }
}
