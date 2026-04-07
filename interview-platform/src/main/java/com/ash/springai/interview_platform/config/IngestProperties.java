package com.ash.springai.interview_platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ingest")
public class IngestProperties {

    private String version = "v2";
    private int cleanupDefaultBatchSize = 50;

    private Markdown markdown = new Markdown();
    private Excel excel = new Excel();
    private Pdf pdf = new Pdf();

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public int getCleanupDefaultBatchSize() {
        return cleanupDefaultBatchSize;
    }

    public void setCleanupDefaultBatchSize(int cleanupDefaultBatchSize) {
        this.cleanupDefaultBatchSize = cleanupDefaultBatchSize;
    }

    public Markdown getMarkdown() {
        return markdown;
    }

    public void setMarkdown(Markdown markdown) {
        this.markdown = markdown;
    }

    public Excel getExcel() {
        return excel;
    }

    public void setExcel(Excel excel) {
        this.excel = excel;
    }

    public Pdf getPdf() {
        return pdf;
    }

    public void setPdf(Pdf pdf) {
        this.pdf = pdf;
    }

    public static class Markdown {
        private int targetMinTokens = 350;
        private int targetMaxTokens = 550;
        private int overlapMinTokens = 50;
        private int overlapMaxTokens = 80;

        public int getTargetMinTokens() {
            return targetMinTokens;
        }

        public void setTargetMinTokens(int targetMinTokens) {
            this.targetMinTokens = targetMinTokens;
        }

        public int getTargetMaxTokens() {
            return targetMaxTokens;
        }

        public void setTargetMaxTokens(int targetMaxTokens) {
            this.targetMaxTokens = targetMaxTokens;
        }

        public int getOverlapMinTokens() {
            return overlapMinTokens;
        }

        public void setOverlapMinTokens(int overlapMinTokens) {
            this.overlapMinTokens = overlapMinTokens;
        }

        public int getOverlapMaxTokens() {
            return overlapMaxTokens;
        }

        public void setOverlapMaxTokens(int overlapMaxTokens) {
            this.overlapMaxTokens = overlapMaxTokens;
        }
    }

    public static class Excel {
        private int targetMinTokens = 200;
        private int targetMaxTokens = 400;
        private int overlapMinTokens = 20;
        private int overlapMaxTokens = 40;

        public int getTargetMinTokens() {
            return targetMinTokens;
        }

        public void setTargetMinTokens(int targetMinTokens) {
            this.targetMinTokens = targetMinTokens;
        }

        public int getTargetMaxTokens() {
            return targetMaxTokens;
        }

        public void setTargetMaxTokens(int targetMaxTokens) {
            this.targetMaxTokens = targetMaxTokens;
        }

        public int getOverlapMinTokens() {
            return overlapMinTokens;
        }

        public void setOverlapMinTokens(int overlapMinTokens) {
            this.overlapMinTokens = overlapMinTokens;
        }

        public int getOverlapMaxTokens() {
            return overlapMaxTokens;
        }

        public void setOverlapMaxTokens(int overlapMaxTokens) {
            this.overlapMaxTokens = overlapMaxTokens;
        }
    }

    public static class Pdf {
        private int targetMinTokens = 400;
        private int targetMaxTokens = 650;
        private int overlapMinTokens = 60;
        private int overlapMaxTokens = 100;

        public int getTargetMinTokens() {
            return targetMinTokens;
        }

        public void setTargetMinTokens(int targetMinTokens) {
            this.targetMinTokens = targetMinTokens;
        }

        public int getTargetMaxTokens() {
            return targetMaxTokens;
        }

        public void setTargetMaxTokens(int targetMaxTokens) {
            this.targetMaxTokens = targetMaxTokens;
        }

        public int getOverlapMinTokens() {
            return overlapMinTokens;
        }

        public void setOverlapMinTokens(int overlapMinTokens) {
            this.overlapMinTokens = overlapMinTokens;
        }

        public int getOverlapMaxTokens() {
            return overlapMaxTokens;
        }

        public void setOverlapMaxTokens(int overlapMaxTokens) {
            this.overlapMaxTokens = overlapMaxTokens;
        }
    }
}
