package com.ash.springai.interview_platform.Entity;

public record SubmitAnswerResponse (
    boolean hasNextQuestion,
    InterviewQuestionDTO nextQuestion,
    int currentIndex,
    int totalQuestions
){
    
}
