package com.ash.springai.interview_platform.controller;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.ash.springai.interview_platform.streaming.DualChannelSse;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;

import com.ash.springai.interview_platform.service.KnowledgeBaseUploadService;
import com.ash.springai.interview_platform.service.KnowledgeBaseQueryService;
import com.ash.springai.interview_platform.service.KnowledgeBaseListService;
import com.ash.springai.interview_platform.service.KnowledgeBaseDeleteService;
import com.ash.springai.interview_platform.service.KnowledgeBaseChunkBrowseService;
import com.ash.springai.interview_platform.service.LegacyKnowledgeBaseCleanupService;
import com.ash.springai.interview_platform.Entity.LegacyCleanupResultDTO;
import com.ash.springai.interview_platform.common.Result;
import com.ash.springai.interview_platform.Entity.KnowledgeBaseListItemDTO;
import com.ash.springai.interview_platform.enums.VectorStatus;
import com.ash.springai.interview_platform.annotation.RateLimit;
import com.ash.springai.interview_platform.Entity.QueryRequest;
import com.ash.springai.interview_platform.Entity.QueryResponse;
import com.ash.springai.interview_platform.Entity.KnowledgeBaseStatsDTO;
import com.ash.springai.interview_platform.Entity.DocumentChunksResponse;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@RestController
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseUploadService uploadService;
    private final KnowledgeBaseQueryService queryService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseDeleteService deleteService;
    private final KnowledgeBaseChunkBrowseService chunkBrowseService;
    private final LegacyKnowledgeBaseCleanupService legacyCleanupService;
    private final ObjectMapper objectMapper;

    @GetMapping("/api/knowledgebase/list")
    public Result<List<KnowledgeBaseListItemDTO>> getAllKnowledgeBases(
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "vectorStatus", required = false) String vectorStatus) {
        
        VectorStatus status = null;
        if (vectorStatus != null && !vectorStatus.isBlank()) {
            try {
                status = VectorStatus.valueOf(vectorStatus.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Result.error("无效的向量化状态: " + vectorStatus);
            }
        }
        
        return Result.success(listService.listKnowledgeBases(status, sortBy));
    }

    @GetMapping("/api/knowledgebase/{id}")
    public Result<KnowledgeBaseListItemDTO> getKnowledgeBase(@PathVariable Long id) {
        return listService.getKnowledgeBase(id)
                .map(Result::success)
                .orElse(Result.error("知识库不存在"));
    }

    @GetMapping("/api/knowledgebase/documents/{documentId}/chunks")
    @RateLimit.Container({
            @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30),
            @RateLimit(dimension = RateLimit.Dimension.IP, count = 30)
    })
    public Result<DocumentChunksResponse> getDocumentChunks(
        @PathVariable Long documentId,
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
        @RequestParam(value = "selectedChunkId", required = false) String selectedChunkId
    ) {
        return Result.success(chunkBrowseService.getDocumentChunks(documentId, page, pageSize, selectedChunkId));
    }

    @DeleteMapping("/api/knowledgebase/{id}")
    public Result<Void> deleteKnowledgeBase(@PathVariable Long id) {
        deleteService.deleteKnowledgeBase(id);
        return Result.success(null);
    }

    @PostMapping("/api/knowledgebase/query")
    @RateLimit.Container({
            @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10),
            @RateLimit(dimension = RateLimit.Dimension.IP, count = 10)
    })
    public Result<QueryResponse> queryKnowledgeBase(@Valid @RequestBody QueryRequest request) {
        return Result.success(queryService.queryKnowledgeBase(request));
    }

    @PostMapping(value = "/api/knowledgebase/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit.Container({
            @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5),
            @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    })
    public Flux<ServerSentEvent<String>> queryKnowledgeBaseStream(@Valid @RequestBody QueryRequest request) {
        log.debug("收到知识库流式查询请求: kbIds={}, question={}, 线程: {} (虚拟线程: {})",
            request.knowledgeBaseIds(), request.question(), Thread.currentThread(), Thread.currentThread().isVirtual());
        return Flux.concat(
            DualChannelSse.partsToSseEvents(
                queryService.answerQuestionStream(request.knowledgeBaseIds(), request.question()),
                objectMapper
            ),
            Flux.just(ServerSentEvent.<String>builder().data("[DONE]").build())
        );
    }

    @GetMapping("/api/knowledgebase/categories")
    public Result<List<String>> getAllCategories() {
        return Result.success(listService.getAllCategories());
    }

    @GetMapping("/api/knowledgebase/category/{category}")
    public Result<List<KnowledgeBaseListItemDTO>> getByCategory(@PathVariable String category) {
        return Result.success(listService.listByCategory(category));
    }

    @GetMapping("/api/knowledgebase/uncategorized")
    public Result<List<KnowledgeBaseListItemDTO>> getUncategorized() {
        return Result.success(listService.listByCategory(null));
    }

    @PutMapping("/api/knowledgebase/{id}/category")
    public Result<Void> updateCategory(@PathVariable Long id, @RequestBody Map<String, String> body) {
        listService.updateCategory(id, body.get("category"));
        return Result.success(null);
    }

    @PostMapping(value = "/api/knowledgebase/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit.Container({
            @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 3),
            @RateLimit(dimension = RateLimit.Dimension.IP, count = 3)
    })
    public Result<Map<String, Object>> uploadKnowledgeBase(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "category", required = false) String category) {
        return Result.success(uploadService.uploadKnowledgeBase(file, name, category));
    }

    @GetMapping("/api/knowledgebase/{id}/download")
    public ResponseEntity<byte[]> downloadKnowledgeBase(@PathVariable Long id) {
        var entity = listService.getEntityForDownload(id);
        byte[] fileContent = listService.downloadFile(id);

        String filename = entity.getOriginalFilename();
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename)
                .header(HttpHeaders.CONTENT_TYPE,
                        entity.getContentType() != null ? entity.getContentType()
                                : MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .body(fileContent);
    }

    @GetMapping("/api/knowledgebase/search")
    public Result<List<KnowledgeBaseListItemDTO>> search(@RequestParam("keyword") String keyword) {
        return Result.success(listService.search(keyword));
    }

    @GetMapping("/api/knowledgebase/stats")
    public Result<KnowledgeBaseStatsDTO> getStatistics() {
        return Result.success(listService.getStatistics());
    }

    @PostMapping("/api/knowledgebase/{id}/revectorize")
    @RateLimit.Container({
            @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2),
            @RateLimit(dimension = RateLimit.Dimension.IP, count = 2)
    })
    public Result<Void> revectorize(@PathVariable Long id) {
        uploadService.revectorize(id);
        return Result.success(null);
    }

    @PostMapping("/api/knowledgebase/legacy/cleanup")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<LegacyCleanupResultDTO> cleanupLegacy(
        @RequestParam(defaultValue = "50") int batchSize,
        Authentication authentication
    ) {
        String operator = authentication != null && authentication.getName() != null
            ? authentication.getName()
            : "unknown";
        return Result.success(legacyCleanupService.cleanupLegacyKnowledgeBases(batchSize, operator));
    }

}
