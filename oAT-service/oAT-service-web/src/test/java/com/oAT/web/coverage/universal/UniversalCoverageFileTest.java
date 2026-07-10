package com.oAT.web.coverage.universal;

import com.oAT.web.esDao.entity.ClassCoverageIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UniversalCoverageFileTest {
    @Test
    void toClassCoverageIndexCarriesBranchTargetsIntoMethodDetails() {
        UniversalCoverageFile file = new UniversalCoverageFile(SourceType.FRONTEND, "src/views/login/index.vue");
        file.setLines(List.of(
                new UniversalCoverageFile.LineCoverage(83, 1),
                new UniversalCoverageFile.LineCoverage(84, 0)
        ));
        file.setFunctions(List.of(
                new UniversalCoverageFile.FunctionCoverage("(anonymous_0)", 83, 90, 1)
        ));
        file.setBranches(List.of(
                new UniversalCoverageFile.BranchCoverage(85, 0, 1, "0"),
                new UniversalCoverageFile.BranchCoverage(86, 1, 0, "0")
        ));

        ClassCoverageIndex index = file.toClassCoverageIndex("app-1");

        assertThat(index.getClassName()).isEqualTo("src/views/login/index.vue");
        assertThat(index.getTotalBranchTargets()).isEqualTo(2);
        assertThat(index.getCoveredBranchTargets()).isEqualTo(1);
        assertThat(index.getMethods()).hasSize(1);

        ClassCoverageIndex.MethodCoverageDetail method = index.getMethods().get(0);
        assertThat(method.getTotalBranchTargetProbeMap()).containsEntry("85:0", List.of(0));
        assertThat(method.getTotalBranchTargetProbeMap()).containsEntry("86:0", List.of(1));
        assertThat(method.getCoveredBranchTargetProbeMap()).containsEntry("85:0", List.of(0));
        assertThat(method.getTotalBranchTargets()).isEqualTo(2);
        assertThat(method.getCoveredBranchTargets()).isEqualTo(1);
    }
}
