package com.oAT.web.service;

import com.oAT.web.service.entity.AIInteractivePageVo;
import com.oAT.web.service.entity.AIInteractiveReplyVo;
import com.oAT.web.service.entity.UserVo;

public interface AIInteractiveService {
    AIInteractivePageVo buildPage(String projectId, UserVo user);

    AIInteractiveReplyVo ask(String projectId, UserVo user, String question, String pageContext, String imageData,
                             String sessionState, String activeSessionId, String sessionSortMode, Boolean timelineExpanded,
                             String memoryScope);

    String saveSessionState(String projectId, UserVo user, String sessionState);

    String clearSessionMemory(String projectId, UserVo user);

    String clearSessionMemory(String projectId, UserVo user, String memoryScope);
}
