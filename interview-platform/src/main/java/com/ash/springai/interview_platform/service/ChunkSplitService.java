package com.ash.springai.interview_platform.service;

import org.springframework.stereotype.Service;

import com.ash.springai.interview_platform.Entity.IngestChunkDTO;
import com.ash.springai.interview_platform.config.IngestProperties;
import com.ash.springai.interview_platform.enums.DocumentType;
import com.ash.springai.interview_platform.service.chunking.ChunkBudgetPolicy;
import com.ash.springai.interview_platform.service.chunking.ChunkingCoreService;
import com.ash.springai.interview_platform.service.chunking.JtokkitTokenCounter;
import com.ash.springai.interview_platform.service.chunking.StructuredChunkCandidate;
import com.ash.springai.interview_platform.service.chunking.TokenCounter;
import com.ash.springai.interview_platform.service.chunking.TokenizerProfileRegistry;
import com.ash.springai.interview_platform.service.strategy.ExcelChunkSplitter;
import com.ash.springai.interview_platform.service.strategy.MarkdownChunkSplitter;
import com.ash.springai.interview_platform.service.strategy.PdfChunkSplitter;

import java.util.List;

@Service
public class ChunkSplitService {

    private final MarkdownChunkSplitter markdownSplitter;
    private final ExcelChunkSplitter excelSplitter;
    private final PdfChunkSplitter pdfSplitter;
    private final IngestProperties ingestProperties;
    private final TokenizerProfileRegistry tokenizerProfileRegistry;
    private final ChunkingCoreService chunkingCoreService;

    public ChunkSplitService(
        MarkdownChunkSplitter markdownSplitter,
        ExcelChunkSplitter excelSplitter,
        PdfChunkSplitter pdfSplitter,
        IngestProperties ingestProperties,
        TokenizerProfileRegistry tokenizerProfileRegistry,
        ChunkingCoreService chunkingCoreService
    ) {
        this.markdownSplitter = markdownSplitter;
        this.excelSplitter = excelSplitter;
        this.pdfSplitter = pdfSplitter;
        this.ingestProperties = ingestProperties;
        this.tokenizerProfileRegistry = tokenizerProfileRegistry;
        this.chunkingCoreService = chunkingCoreService;
    }

    public List<IngestChunkDTO> split(DocumentType type, String content) {
        return split(type, content, tokenizerProfileRegistry.requireDefault().id());
    }

    public List<IngestChunkDTO> split(DocumentType type, String content, String tokenizerProfileId) {
        var profile = tokenizerProfileRegistry.require(tokenizerProfileId);
        ChunkBudgetPolicy budget = switch (type) {
            case EXCEL_TABLE -> new ChunkBudgetPolicy(
                ingestProperties.getExcel().getTargetMaxTokens(),
                ingestProperties.getExcel().getOverlapMaxTokens()
            );
            case PDF_LONGFORM -> new ChunkBudgetPolicy(
                ingestProperties.getPdf().getTargetMaxTokens(),
                ingestProperties.getPdf().getOverlapMaxTokens()
            );
            default -> new ChunkBudgetPolicy(
                ingestProperties.getMarkdown().getTargetMaxTokens(),
                ingestProperties.getMarkdown().getOverlapMaxTokens()
            );
        };
        List<StructuredChunkCandidate> candidates = switch (type) {
            case EXCEL_TABLE -> excelSplitter.splitStructured(content);
            case PDF_LONGFORM -> pdfSplitter.splitStructured(content);
            default -> markdownSplitter.splitStructured(content);
        };
        TokenCounter tokenCounter = new JtokkitTokenCounter(profile.encoding());
        return chunkingCoreService.chunk(candidates, budget, tokenCounter);
    }
}
