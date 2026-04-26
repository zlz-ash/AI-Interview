package com.ash.springai.interview_platform.controller;

import com.ash.springai.interview_platform.common.Result;
import com.ash.springai.interview_platform.service.chunking.TokenizerProfileRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class TokenizerProfileController {

    private final TokenizerProfileRegistry tokenizerProfileRegistry;

    @GetMapping("/api/knowledgebase/tokenizer-profiles")
    public Result<List<TokenizerProfileOptionDTO>> listTokenizerProfiles() {
        String defaultProfileId = tokenizerProfileRegistry.getDefaultProfileId();
        List<TokenizerProfileOptionDTO> options = tokenizerProfileRegistry.listProfiles().stream()
            .map(profile -> new TokenizerProfileOptionDTO(
                profile.id(),
                profile.model(),
                profile.encoding(),
                profile.id().equals(defaultProfileId)
            ))
            .toList();
        return Result.success(options);
    }

    public record TokenizerProfileOptionDTO(
        String id,
        String model,
        String encoding,
        boolean isDefault
    ) {}
}
