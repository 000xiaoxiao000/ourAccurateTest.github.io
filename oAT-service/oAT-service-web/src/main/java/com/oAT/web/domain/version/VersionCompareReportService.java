package com.oAT.web.domain.version;

import com.oAT.web.common.Job;
import com.oAT.web.common.compare.CompareResult;
import com.oAT.web.persistence.VersionCenterRepository;
import com.oAT.web.persistence.entity.VersionCenterIndex;
import com.oAT.web.persistence.entity.VersionCompareReport;
import com.oAT.web.service.entity.CompareJobVo;
import com.oAT.web.service.entity.VersionCompareReportVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class VersionCompareReportService {
    private static final Logger logger = LoggerFactory.getLogger(VersionCompareReportService.class);

    private final VersionCenterRepository versionCenterRepository;

    public VersionCompareReportService(VersionCenterRepository versionCenterRepository) {
        this.versionCenterRepository = versionCenterRepository;
    }

    public void saveCompareReport(Job<CompareJobVo> job) {
        CompareJobVo vo = job.getData();
        VersionCompareReport report = new VersionCompareReport();
        report.setProjectId(vo.getProjectId());
        report.setAppId(vo.getAppId());
        report.setJobId(job.getId());
        report.setJobLog(vo.getLog());
        report.setJobName(vo.getName());

        String sourceVersion = vo.getGitOldCommit();
        if (!StringUtils.hasText(sourceVersion)) {
            sourceVersion = vo.getSourceFile();
        }
        report.setSourceVersion(sourceVersion);

        String targetVersion = vo.getGitNewCommit();
        if (!StringUtils.hasText(targetVersion)) {
            targetVersion = vo.getTargetFile();
        }
        report.setTargetVersion(targetVersion);

        report.setGitBranch(vo.getGitBranch());
        report.setGitOldCommit(vo.getGitOldCommit());
        report.setGitNewCommit(vo.getGitNewCommit());

        List<VersionCompareReport.Difference> listDifference = new ArrayList<>();
        List<CompareResult> diffsToIterate = vo.getDifferences() == null ? Collections.emptyList() : vo.getDifferences();
        for (CompareResult difference : diffsToIterate) {
            listDifference.add(new VersionCompareReport.Difference("class", difference.getModel().toString(),
                    difference.getClassName()));
            for (CompareResult.Method method : difference.getMethods()) {
                String methodValue = difference.getClassName() + "\t" + method.getName() + "\t" + (method.getDesc() == null ? "" : method.getDesc());
                listDifference.add(new VersionCompareReport.Difference("method", method.getModel().toString(), methodValue));
            }
        }
        report.setDifferences(listDifference.toArray(new VersionCompareReport.Difference[0]));

        report.setCases(new VersionCompareReport.ImpactCase[0]);

        report.setAddClassCount(vo.getAddClassCount());
        report.setUpdateClassCount(vo.getUpdateClassCount());
        report.setDeleteClassCount(vo.getDeleteClassCount());
        report.setAddMethodCount(vo.getAddMethodCount());
        report.setUpdateMethodCount(vo.getUpdateMethodCount());
        report.setDeleteMethodCount(vo.getDeleteMethodCount());
        report.setImpactCaseCount(0);

        job.getLogger().info(String.format("开始保存版本比对报告 id=%s 差异数=%s",
                job.getId(), listDifference.size()));
        logger.info("保存版本比对报告 id={} diffs={}", job.getId(), listDifference.size());

        VersionCenterIndex index = new VersionCenterIndex(report);
        index.setId(job.getId());
        try {
            index = versionCenterRepository.save(index);
            boolean saved = versionCenterRepository.findById(index.getId()).isPresent();
            Assert.isTrue(saved, "比对报告保存失败，id=" + index.getId());
            job.getLogger().info("比对报告保存成功 id=" + index.getId());
        } catch (Exception e) {
            job.getLogger().error("比对报告保存失败 id=" + job.getId() + " 错误=" + e.getMessage());
            logger.error("比对报告保存失败 id={} diffs={}", job.getId(), listDifference.size(), e);
            throw e;
        }
    }

    public VersionCompareReport getCompareReport(String compareId) {
        Assert.hasText(compareId, "参数compareId不能为空");

        Optional<VersionCenterIndex> optional = versionCenterRepository.findById(compareId);
        Assert.isTrue(optional.isPresent(), String.format("找不到id=%s的比对报告", compareId));

        VersionCenterIndex index = optional.get();
        Assert.isTrue("compareReport".equalsIgnoreCase(index.getType()), String.format("id=%s对应的记录不是比对报告", compareId));
        Assert.notNull(index.getCompareReport(), String.format("id=%s对应的记录不是比对报告", compareId));

        VersionCompareReport report = index.getCompareReport();
        report.setCreateTime(index.getCreateTime());
        return report;
    }

    public Page<VersionCompareReportVo> getCompareReportList(String projectId, String appId, Pageable pageable) {
        Page<VersionCenterIndex> page = versionCenterRepository.findCompareReportPage(projectId, appId, pageable);
        List<VersionCompareReportVo> result = page.getContent().stream()
                .map(this::convertCompareReport)
                .collect(Collectors.toList());
        return new PageImpl<>(result, pageable, page.getTotalElements());
    }

    public void deleteCompareReport(String projectId, String reportId) {
        Optional<VersionCenterIndex> index = versionCenterRepository.findById(reportId);
        Assert.isTrue(index.isPresent(), "找不到比对报告，id=" + reportId);
        Assert.isTrue("compareReport".equalsIgnoreCase(index.get().getType()), "找不到比对报告，id=" + reportId);
        Assert.isTrue(index.get().getCompareReport().getProjectId().equalsIgnoreCase(projectId), "项目ID不符，非法的操作");
        versionCenterRepository.deleteById(reportId);
    }

    private VersionCompareReportVo convertCompareReport(VersionCenterIndex index) {
        VersionCompareReport reportIndex = index.getCompareReport();
        VersionCompareReportVo report = new VersionCompareReportVo(index.getId(),
                reportIndex == null ? null : reportIndex.getJobName());
        if (reportIndex != null) {
            report.setSourceVersion(reportIndex.getSourceVersion());
            report.setTargetVersion(reportIndex.getTargetVersion());
            report.setGitBranch(reportIndex.getGitBranch());
            report.setGitOldCommit(reportIndex.getGitOldCommit());
            report.setGitNewCommit(reportIndex.getGitNewCommit());
            report.setAddClassCount(reportIndex.getAddClassCount());
            report.setUpdateClassCount(reportIndex.getUpdateClassCount());
            report.setDeleteClassCount(reportIndex.getDeleteClassCount());
            report.setAddMethodCount(reportIndex.getAddMethodCount());
            report.setUpdateMethodCount(reportIndex.getUpdateMethodCount());
            report.setDeleteMethodCount(reportIndex.getDeleteMethodCount());
            report.setImpactCaseCount(reportIndex.getImpactCaseCount());
        }
        report.setCreateTime(index.getCreateTime());
        return report;
    }
}
