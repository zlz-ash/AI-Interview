package com.ash.springai.interview_platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.ingest.tokenizer")
public class TokenizerProfilesProperties {

    private String defaultProfileId = "dashscope-text-embedding-v3";
    private List<Profile> profiles = new ArrayList<>();

    public String getDefaultProfileId() {
        return defaultProfileId;
    }

    public void setDefaultProfileId(String defaultProfileId) {
        this.defaultProfileId = defaultProfileId;
    }

    public List<Profile> getProfiles() {
        return profiles;
    }

    public void setProfiles(List<Profile> profiles) {
        this.profiles = profiles;
    }

    public static class Profile {
        private String id;
        private String model;
        private String encoding = "cl100k_base";

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getEncoding() {
            return encoding;
        }

        public void setEncoding(String encoding) {
            this.encoding = encoding;
        }
    }
}
