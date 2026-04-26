package com.ash.springai.interview_platform.service.chunking;

import com.ash.springai.interview_platform.config.TokenizerProfilesProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenizerProfileRegistryTests {

    @Test
    void shouldReturnRegisteredProfile() {
        TokenizerProfilesProperties props = new TokenizerProfilesProperties();
        TokenizerProfilesProperties.Profile profile = new TokenizerProfilesProperties.Profile();
        profile.setId("dashscope-text-embedding-v3");
        profile.setModel("text-embedding-v3");
        profile.setEncoding("cl100k_base");
        props.getProfiles().add(profile);

        TokenizerProfileRegistry registry = new TokenizerProfileRegistry(props);
        TokenizerProfileRegistry.ProfileView view = registry.require("dashscope-text-embedding-v3");
        assertEquals("text-embedding-v3", view.model());
    }

    @Test
    void shouldThrowWhenProfileMissing() {
        TokenizerProfileRegistry registry = new TokenizerProfileRegistry(new TokenizerProfilesProperties());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> registry.require("missing"));
        assertTrue(ex.getMessage().contains("未知 tokenizer profile"));
    }
}
