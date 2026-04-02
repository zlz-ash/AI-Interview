package com.ash.springai.interview_platform.Entity;

public record InterviewQuestionDTO (
    int questionIndex,
    String question,
    QuestionType type,
    String category,      // 问题类别：项目经历、Java基础、集合、并发、MySQL、Redis、Spring、SpringBoot
    String userAnswer,    // 用户回答
    Integer score,        // 单题得分 (0-100)
    String feedback,      // 单题反馈
    boolean isFollowUp,   // 是否为追问
    Integer parentQuestionIndex 
){

    public enum QuestionType {
        PROJECT,          // 项目经历
        JAVA_BASIC,       // Java基础
        JAVA_COLLECTION,  // Java集合
        JAVA_CONCURRENT,  // Java并发
        MYSQL,            // MySQL
        REDIS,            // Redis
        SPRING,           // Spring
        SPRING_BOOT       // Spring Boot
    }

    public static InterviewQuestionDTO create(int index, String question, QuestionType type, String category) {
        return new InterviewQuestionDTO(index, question, type, category, null, null, null, false, null);
    }

    public static InterviewQuestionDTO create(
            int index,
            String question,
            QuestionType type,
            String category,
            boolean isFollowUp,
            Integer parentQuestionIndex) {
        return new InterviewQuestionDTO(index, question, type, category, null, null, null, isFollowUp, parentQuestionIndex);
    }

    public InterviewQuestionDTO withAnswer(String answer) {
        return new InterviewQuestionDTO(
            questionIndex, question, type, category, answer, score, feedback, isFollowUp, parentQuestionIndex);
    }

    public InterviewQuestionDTO withEvaluation(int score, String feedback) {
        return new InterviewQuestionDTO(
            questionIndex, question, type, category, userAnswer, score, feedback, isFollowUp, parentQuestionIndex);
    }
}
