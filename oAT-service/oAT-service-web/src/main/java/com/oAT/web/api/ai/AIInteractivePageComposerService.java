package com.oAT.web.api.ai;

import com.oAT.web.common.PaletteColors;
import com.oAT.web.service.entity.AIAbilityCardVo;
import com.oAT.web.service.entity.AppVo;
import com.oAT.web.service.entity.ProjectVo;
import com.oAT.web.service.entity.UserVo;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AIInteractivePageComposerService {

    private static final String[] MASCOT_NAMES = {"知秋", "阿涌", "小溯", "言希", "跃链", "拾一"};
    private static final String[] MASCOT_ROLES = {"数据侦察员", "链路向导", "项目陪跑员", "交互分析官", "洞察助手"};
    private static final String[] MASCOT_MOODS = {"专注", "活跃", "机敏", "稳健", "可靠"};

    public int countOnlineApps(List<AppVo> apps) {
        int count = 0;
        for (AppVo app : apps) {
            if (app.getOnlineCount() > 0) {
                count++;
            }
        }
        return count;
    }

    public Map<String, String> buildMascot(ProjectVo project) {
        int seed = positiveHash(project.getId() + ":" + project.getName());
        Map<String, String> mascot = new HashMap<>();
        mascot.put("mascotName", pick(MASCOT_NAMES, seed));
        mascot.put("mascotRole", pick(MASCOT_ROLES, seed / 2 + 7));
        mascot.put("mascotMood", pick(MASCOT_MOODS, seed / 3 + 11));
        mascot.put("mascotPrimary", PaletteColors.pickPrimary(seed / 5 + 13));
        mascot.put("mascotAccent", PaletteColors.pickAccent(seed / 7 + 17));
        mascot.put("mascotHalo", PaletteColors.pickHalo(seed / 11 + 19));
        return mascot;
    }

    public String buildProjectSummary(ProjectVo project, List<AppVo> apps) {
        int online = countOnlineApps(apps);
        return String.format("项目「%s」当前有 %d 个应用，其中 %d 个在线。",
                project.getName(), apps.size(), online);
    }

    public String buildWelcomeMessage(UserVo user, ProjectVo project, List<AppVo> apps, String mascotName) {
        int online = countOnlineApps(apps);
        return String.format("你好，%s！我是 %s，你的精准测试助手。当前项目「%s」有 %d 个应用，%d 个在线运行。有什么我可以帮助你的吗？",
                user.getName(), StringUtils.hasText(mascotName) ? mascotName : "AI", project.getName(), apps.size(), online);
    }

    public List<String> buildStarterQuestions(List<AppVo> apps) {
        List<String> questions = new ArrayList<>();
        questions.add("这个项目有哪些代码风险？");
        questions.add("有哪些应用需要关注？");
        questions.add("帮我分析一下最近的测试情况");
        if (!apps.isEmpty()) {
            questions.add("应用 " + apps.get(0).getName() + " 的代码结构如何？");
        }
        return questions;
    }

    public List<AIAbilityCardVo> buildAbilityCards(List<AppVo> apps) {
        List<AIAbilityCardVo> cards = new ArrayList<>();
        cards.add(new AIAbilityCardVo("总应用数", String.valueOf(apps.size()), "项目下的应用总数"));
        cards.add(new AIAbilityCardVo("代码分析", "可用", "基于静态源码和版本差异分析"));
        return cards;
    }

    public String buildMascotHint(List<AppVo> apps) {
        int online = countOnlineApps(apps);
        if (online == 0) {
            return "当前没有应用在线，你可以启动一些应用来开始测试。";
        }
        if (online < 3) {
            return "有少量应用在线，可以开始进行测试分析了。";
        }
        return "多个应用在线运行中，随时可以进行分析和查询。";
    }

    private int positiveHash(String value) {
        int hash = value == null ? 0 : value.hashCode();
        if (hash == Integer.MIN_VALUE) {
            return 0;
        }
        return Math.abs(hash);
    }

    private String pick(String[] values, int seed) {
        if (values.length == 0) {
            return "";
        }
        return values[seed % values.length];
    }
}
