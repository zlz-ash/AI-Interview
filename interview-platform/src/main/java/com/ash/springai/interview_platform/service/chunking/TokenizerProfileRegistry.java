package com.ash.springai.interview_platform.service.chunking;

import com.ash.springai.interview_platform.config.TokenizerProfilesProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class TokenizerProfileRegistry {

    public record ProfileView(String id, String model, String encoding) {}

    private final Map<String, ProfileView> profileMap;
    private final String defaultProfileId;

    public TokenizerProfileRegistry(TokenizerProfilesProperties properties) {
        this.defaultProfileId = properties.getDefaultProfileId();
        this.profileMap = properties.getProfiles().stream()
            .filter(p -> p.getId() != null && !p.getId().isBlank())
            .collect(Collectors.toUnmodifiableMap(
                TokenizerProfilesProperties.Profile::getId,
                p -> new ProfileView(
                    p.getId(),
                    Objects.toString(p.getModel(), ""),
                    Objects.toString(p.getEncoding(), "cl100k_base")
                )
            ));
    }

    public ProfileView require(String profileId) {
        ProfileView view = profileMap.get(profileId);
        if (view == null) {
            throw new IllegalArgumentException("未知 tokenizer profile: " + profileId);
        }
        return view;
    }

    public ProfileView requireDefault() {
        return require(defaultProfileId);
    }

    public String getDefaultProfileId() {
        return defaultProfileId;
    }

    public List<ProfileView> listProfiles() {
        return profileMap.values().stream().toList();
    }
}
