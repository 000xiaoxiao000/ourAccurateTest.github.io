package com.oAT.web.control.api;

import com.oAT.web.api.ai.AIInteractiveApiPayloads.*;
import com.oAT.web.control.entity.ResultNotified;
import com.oAT.web.service.AIInteractiveService;
import com.oAT.web.service.entity.AIAbilityCardVo;
import com.oAT.web.service.entity.AIActionVo;
import com.oAT.web.service.entity.AIInteractivePageVo;
import com.oAT.web.service.entity.AIInteractiveReplyVo;
import com.oAT.web.service.entity.AIQuickLinkVo;
import com.oAT.web.service.entity.UserVo;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/ai")
public class AIInteractiveApiControl {

    private final AIInteractiveService aiInteractiveService;

    public AIInteractiveApiControl(AIInteractiveService aiInteractiveService) {
        this.aiInteractiveService = aiInteractiveService;
    }

    @GetMapping("/context")
    public ResultNotified<AIInteractivePagePayload> context(@PathVariable String projectId,
                                                            @SessionAttribute UserVo user) {
        AIInteractivePageVo page = aiInteractiveService.buildPage(projectId, user);
        AIInteractivePagePayload payload = new AIInteractivePagePayload();
        payload.setProjectId(page.getProjectId());
        payload.setProjectName(page.getProjectName());
        payload.setProjectSummary(page.getProjectSummary());
        payload.setWelcomeMessage(page.getWelcomeMessage());
        payload.setMascotHint(page.getMascotHint());
        payload.setOnlineAppCount(page.getOnlineAppCount());
        payload.setAppCount(page.getAppCount());
        payload.setAppNames(page.getAppNames());
        payload.setStarterQuestions(page.getStarterQuestions());
        payload.setAbilityCards(page.getAbilityCards());
        payload.setQuickLinks(page.getQuickLinks());
        payload.setMascot(page.getMascot());
        payload.setAiTimeout(page.getAiTimeout());
        payload.setSessionState(page.getSessionState());
        return new ResultNotified<>(true, "获取 AI 工作台上下文成功", payload);
    }

    @PostMapping("/ask")
    public ResultNotified<AIInteractiveReplyVo> ask(@PathVariable String projectId,
                                                    @SessionAttribute UserVo user,
                                                    @RequestBody AskRequest request) {
        String question = request.getQuestion() == null ? "" : request.getQuestion().trim();
        if (!StringUtils.hasText(question) && !StringUtils.hasText(request.getImageData())) {
            return new ResultNotified<>(false, "请输入您想了解的内容或上传图片");
        }
        AIInteractiveReplyVo reply = aiInteractiveService.ask(
                projectId,
                user,
                question,
                request.getPageContext(),
                request.getImageData(),
                request.getSessionState(),
                request.getActiveSessionId(),
                request.getSessionSortMode(),
                request.getTimelineExpanded(),
                request.getMemoryScope()
        );
        return new ResultNotified<>(true, "分析完成", reply);
    }

    @PostMapping("/session-state")
    public ResultNotified<String> saveSessionState(@PathVariable String projectId,
                                                   @SessionAttribute UserVo user,
                                                   @RequestBody SessionStateRequest request) {
        return new ResultNotified<>(true, "保存成功",
                aiInteractiveService.saveSessionState(projectId, user, request.getSessionState()));
    }

    @PostMapping("/session-state/clear")
    public ResultNotified<String> clearSessionState(@PathVariable String projectId,
                                                    @SessionAttribute UserVo user,
                                                    @RequestBody(required = false) SessionStateRequest request) {
        String memoryScope = request == null ? null : request.getMemoryScope();
        return new ResultNotified<>(true, "记忆已清空",
                aiInteractiveService.clearSessionMemory(projectId, user, memoryScope));
    }

}
