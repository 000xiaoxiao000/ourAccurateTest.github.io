package com.oAT.web.verification;

import com.oAT.web.verification.model.VerificationModels.AcceptanceCriterion;
import com.oAT.web.verification.model.VerificationModels.TestcaseProjection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationProjectionParserTest {
    private final VerificationProjectionParser parser = new VerificationProjectionParser();

    @Test
    void parsesExplicitAndConstraintAcceptanceCriteria() {
        String content = """
                # REQ-LOGIN-01 登录失败锁定
                AC-01: 密码连续错误 5 次后必须锁定账户
                - 锁定时间至少 30 分钟

                # REQ-LOGIN-02 解锁
                验收标准1：管理员可以手工解锁
                """;

        List<AcceptanceCriterion> result = parser.parseRequirements("base-1", content);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(AcceptanceCriterion::requirementKey)
                .containsExactly("REQ-LOGIN-01", "REQ-LOGIN-01", "REQ-LOGIN-02");
        assertThat(result.get(0).content()).contains("5 次");
        assertThat(result.get(1).sourceLocator()).isEqualTo("line:3");
    }

    @Test
    void flagsAmbiguousCriterion() {
        List<AcceptanceCriterion> result = parser.parseRequirements("base-1", "REQ-1 性能\nAC-1 系统应尽快返回合理结果");
        assertThat(result).singleElement().extracting(AcceptanceCriterion::ambiguity).isEqualTo(true);
    }

    @Test
    void parsesCsvTestcasesIntoReadOnlyProjections() {
        String content = """
                用例ID,标题,前置条件,步骤,测试数据,预期结果,需求ID
                TC-01,第五次失败锁定,账户未锁定,连续输入错误密码,错误密码,账户被锁定,REQ-LOGIN-01
                """;

        List<TestcaseProjection> result = parser.parseTestcases("base-1", "cases.csv", content);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.externalKey()).isEqualTo("TC-01");
            assertThat(item.expected()).isEqualTo("账户被锁定");
            assertThat(item.requirementRefs()).isEqualTo("REQ-LOGIN-01");
        });
    }

    @Test
    void parsesJsonTestcases() {
        String content = """
                [{"id":"TC-9","title":"解锁","steps":"管理员点击解锁","expected":"账户恢复","requirementId":"REQ-2"}]
                """;
        List<TestcaseProjection> result = parser.parseTestcases("base-1", "cases.json", content);
        assertThat(result).singleElement().extracting(TestcaseProjection::externalKey).isEqualTo("TC-9");
    }
}
