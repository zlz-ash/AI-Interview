package com.ash.springai.interview_platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_SPRING_CONTEXT_IT", matches = "(?i)true")
class InterviewPlatformApplicationTests {

	@Test
	void contextLoads() {
	}

}
