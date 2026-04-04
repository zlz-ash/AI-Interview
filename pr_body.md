## Summary
- add dynamic batching policy for answer evaluation with configurable thresholds and safer merge logic for missing/misaligned question indexes
- add unit tests for batching policy and fallback merge behavior when structured output is incomplete
- add real API integration test scaffolding and question-bank fixtures, and switch chat config to OpenRouter model settings

## Test plan
- [x] `mvn "-Dtest=AnswerEvaluationMergeFallbackTests,AnswerEvaluationBatchingPolicyTests" test`
- [x] `mvn "-Dtest=AnswerEvaluationServiceApiIntegrationTests#shouldEvaluateSmokeQuestionSetWithRealApi" test`
- [x] verify retry count and end-to-end timing from integration test logs
