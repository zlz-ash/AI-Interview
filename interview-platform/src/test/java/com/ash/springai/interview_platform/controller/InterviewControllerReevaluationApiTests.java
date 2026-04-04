package com.ash.springai.interview_platform.controller;

import com.ash.springai.interview_platform.service.InterviewHistoryService;
import com.ash.springai.interview_platform.service.InterviewPersistenceService;
import com.ash.springai.interview_platform.service.InterviewSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InterviewControllerReevaluationApiTests {

    private MockMvc mockMvc;
    private InterviewSessionService sessionService;

    @BeforeEach
    void setUp() {
        this.sessionService = mock(InterviewSessionService.class);
        InterviewHistoryService historyService = mock(InterviewHistoryService.class);
        InterviewPersistenceService persistenceService = mock(InterviewPersistenceService.class);
        InterviewController controller = new InterviewController(sessionService, historyService, persistenceService);
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldTriggerReevaluationEndpoint() throws Exception {
        mockMvc.perform(post("/api/interview/sessions/s1/reevaluate"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        verify(sessionService).requestReevaluation("s1");
    }
}
