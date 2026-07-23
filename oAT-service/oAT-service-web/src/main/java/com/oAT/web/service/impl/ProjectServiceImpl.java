package com.oAT.web.service.impl;

import com.oAT.web.persistence.SystemRepository;
import com.oAT.web.persistence.entity.LabelGroup;
import com.oAT.web.persistence.entity.Project;
import com.oAT.web.persistence.entity.ProjectMember;
import com.oAT.web.persistence.entity.SystemIndex;
import com.oAT.web.exceptions.UserOperationException;
import com.oAT.web.service.ProjectService;
import com.oAT.web.service.entity.*;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.DigestUtils;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ProjectServiceImpl implements ProjectService {

    @Autowired
    private SystemRepository systemRepository;

    @Override
    public ProjectVo createProject(CreateProjectParam param) {
        Project project = new Project();
        project.setCreate(param.userId);
        project.setName(param.name);
        project.setDescribe(param.describe);

        //  保存至system 索引当中
        SystemIndex projectIndex = systemRepository.save(new SystemIndex(project));

        // 创建人初始为项目第一个成员
        ProjectMember member = new ProjectMember();
        member.setMemberId(param.userId);
        member.setProjectId(projectIndex.getId());
        member.setRole(ProjectMemberVo.Role.owner.toString());
        SystemIndex projectMemberIndex = new SystemIndex(member);
        systemRepository.save(projectMemberIndex);

        // 封装返回结果
        ProjectVo pv = new ProjectVo();
        BeanUtils.copyProperties(projectIndex, pv);
        BeanUtils.copyProperties(projectIndex.getProject(), pv);
        fillProjectCreatorDisplayName(pv);
        fillProjectMemberCount(pv);
        return pv;
    }

    @Override
    public List<ProjectVo> findProjectByMemberId(String memberId) {
        List<ProjectVo> result = new ArrayList<>();
        // 获取指定用户所有项目ID
        List<SystemIndex> list = systemRepository.findByProjectMember_MemberId(memberId, PageRequest.of(0, 1000));
        List<String> ids = new ArrayList<>(list.size());
        for (SystemIndex index : list) {
            ids.add(index.getProjectMember().getProjectId());
        }
        if (ids.isEmpty()) {
            return new ArrayList<>();
        }
        for (SystemIndex projectIndex : systemRepository.findAllById(ids)) {
            if (projectIndex == null || projectIndex.getProject() == null || !"project".equals(projectIndex.getType())) {
                continue;
            }
            result.add(convertProject(projectIndex));
        }
        return result;
    }


    /**
     * 保存标签分组
     *
     * @param groupParam
     */
    @Override
    public LabelGroupVo doSaveLabelGroup(LabelGroupVo groupParam) {
        Assert.notNull(groupParam, "param 'group' must be not null");
        Assert.hasText(groupParam.getProjectid(), "param 'group.projectid' must be not null");
        Assert.hasText(groupParam.getType(), "param 'group.type' must be not null");
        Assert.notEmpty(groupParam.getLabels(), "param 'group.labels' must be empty");
        SystemIndex index;
        LabelGroup labelGroup = new LabelGroup();
        if (groupParam.getId() == null) {// 新增
            index = new SystemIndex(labelGroup);
            index.setId(groupParam.getId());

        } else { //修改
            Optional<SystemIndex> o = systemRepository.findById(groupParam.getId());
            Assert.isTrue(o.isPresent(), "not found labelGroup by id id=" + groupParam.getId());
            index = o.get();
        }
        BeanUtils.copyProperties(groupParam, labelGroup);
        index.setLabelGroup(labelGroup);
        index.setUpdateTime(new java.util.Date());
        systemRepository.save(index);
        return convertLable(index);
    }


    /**
     * 获取标签组
     *
     * @param projectId
     * @param type
     */
    @Override
    public List<LabelGroupVo> getLableGroup(String projectId, LableType type) {
        List<SystemIndex> list = systemRepository.findByLabelGroup_ProjectidAndLabelGroup_Type(projectId,
                type.toString());
        List<LabelGroupVo> result = new ArrayList<>();
        for (SystemIndex index : list) {
            result.add(convertLable(index));
        }
        return result;
    }

    @Override
    public ProjectMemberVo addProjectMember(String projectId, String userId) {
        Assert.isTrue(systemRepository.existsById(projectId), "找不到指定项目 id=" + projectId);
        Assert.isTrue(systemRepository.existsById(userId), "找不到指定用户 id=" + userId);
        List<SystemIndex> list = systemRepository.findByProjectMember_ProjectIdAndProjectMember_MemberId(projectId,
                userId);
        Assert.isTrue(list.isEmpty(), String.format("该用户已添加至当前项目"));
        SystemIndex index = new SystemIndex(new ProjectMember(projectId, userId,
                ProjectMemberVo.Role.normal.toString()));
        index = systemRepository.save(index);
        return convertProjectMember(index);
    }


    @Override
    public List<ProjectMemberVo> getProjectMembers(String projectId) {
        List<SystemIndex> list = systemRepository.findByProjectMember_ProjectId(projectId, PageRequest.of(0, 1000));
        List<ProjectMemberVo> result = new ArrayList<>(list.size());
        for (SystemIndex index : list) {
            result.add(convertProjectMember(index));
        }

        // 设置名称 与 Email
        Iterable<SystemIndex> users =
                systemRepository.findAllById(result.stream().map(a -> a.getMemberId()).collect(Collectors.toList()));
        users.forEach(user -> {
            result.stream().filter(a -> a.getMemberId().equals(user.getId())).findAny().ifPresent(c -> {
                c.setMemberName(user.getUser().getName());
                c.setMemberEmail(user.getUser().getEmail());
            });
        });

        return result;
    }


    private ProjectMemberVo convertProjectMember(SystemIndex index) {
        ProjectMemberVo vo = new ProjectMemberVo();
        vo.setId(index.getId());
        vo.setCreateTime(index.getCreateTime());
        vo.setProjectId(index.getProjectMember().getProjectId());
        vo.setMemberId(index.getProjectMember().getMemberId());

        vo.setRole(ProjectMemberVo.Role.valueOf(index.getProjectMember().getRole()));
        return vo;
    }

    private LabelGroupVo convertLable(SystemIndex index) {
        LabelGroupVo vo = new LabelGroupVo();
        vo.setId(index.getId());
        vo.setUpdateTime(index.getUpdateTime());
        BeanUtils.copyProperties(index.getLabelGroup(), vo);
        return vo;
    }

    @Override
    public ProjectVo getProjectByProjectIdAndMemberId(String projectId, String memberId) {
        List<SystemIndex> list = systemRepository.findByProjectMember_ProjectIdAndProjectMember_MemberId(projectId,
                memberId);
        if (list.isEmpty()) {
            return null;
        }
        Optional<SystemIndex> o = systemRepository.findById(projectId);
        if (!o.isPresent() || o.get().getProject() == null) {
            return null;
        }
        return convertProject(o.get());
    }

    @Override
    public ProjectVo getProject(String projectId) {
        Optional<SystemIndex> projectIndex = systemRepository.findById(projectId);
        if (!projectIndex.isPresent() || projectIndex.get().getProject() == null) {
            return null;
        }
        return convertProject(projectIndex.get());
    }

    @Override
    public void deleteProject(String projectId, String userId, String password) throws UserOperationException {
        Assert.notNull(password, "param 'password' must be not null");
        Assert.notNull(projectId, "param 'projectId' must be not null");
        Assert.notNull(userId, "param 'userId' must be not null");

        // Verify user password
        Optional<SystemIndex> userIndex = systemRepository.findById(userId);
        if (!userIndex.isPresent() || userIndex.get().getUser() == null) {
            throw new UserOperationException("用户不存在");
        }

        String md5Pwd = DigestUtils.md5DigestAsHex(password.getBytes(Charset.forName("UTF-8")));
        if (!userIndex.get().getUser().getPassword().equals(md5Pwd)) {
            throw new UserOperationException("密码输入有误!");
        }

        // Verify project exists
        Optional<SystemIndex> projectIndex = systemRepository.findById(projectId);
        if (!projectIndex.isPresent() || projectIndex.get().getProject() == null) {
            throw new UserOperationException("项目不存在");
        }

        // 1. Delete all project members related to this project
        List<SystemIndex> members = systemRepository.findByProjectMember_ProjectId(projectId, Pageable.unpaged());
        if (members != null && !members.isEmpty()) {
            systemRepository.deleteAll(members);
        }

        // 2. Delete the project itself
        systemRepository.deleteById(projectId);
    }

    @Override
    public List<LabelGroup.Label> getLables(String projectId, LableType type) {
        List<LabelGroup.Label> result = new ArrayList<>();
        List<LabelGroupVo> list = getLableGroup(projectId, type);
        for (LabelGroupVo vo : list) {
            for (LabelGroup.Label label : vo.getLabels()) {
                if (!result.contains(label)) {
                    result.add(label);
                }
            }
        }
        return result;
    }

    @Override
    public List<LabelGroup.Label> getLables(String projectId, LableType type, String... labels) {
        List<LabelGroup.Label> allLabel = getLables(projectId, type);
        List<LabelGroup.Label> selectLab = new ArrayList<>();
        for (String label : labels) {
            for (LabelGroup.Label lab : allLabel) {
                if (lab.getName().equals(label)) {
                    selectLab.add(lab);
                }
            }
        }

        return selectLab;
    }

    @Override
    public void deleteProjectMember(String projectId, String projectMemberId) {
        Assert.isTrue(systemRepository.existsById(projectId), "指定项目不存在");
        Optional<SystemIndex> o = systemRepository.findById(projectMemberId);
        Assert.isTrue(o.isPresent(), "指定项目下不存在该用户，id=" + projectMemberId);
        Assert.isTrue(o.get().getProjectMember().getProjectId().equals(projectId), "当前用户不属于指定项目，id=" + projectMemberId);
        systemRepository.deleteById(projectMemberId);
    }

    @Override
    public void updateProjectMemberRole(String projectId, String projectMemberId, ProjectMemberVo.Role role) {
        Assert.isTrue(systemRepository.existsById(projectId), "指定项目不存在");
        Optional<SystemIndex> o = systemRepository.findById(projectMemberId);
        Assert.isTrue(o.isPresent(), "指定项目下不存在该用户，id=" + projectMemberId);
        SystemIndex index = o.get();
        index.setUpdateTime(new java.util.Date());
        index.getProjectMember().setRole(role.toString());
        systemRepository.save(index);
    }

    @Override
    public ProjectVo updateProject(ProjectVo projectVo) {
        Optional<SystemIndex> o = systemRepository.findById(projectVo.getId());
        Assert.isTrue(o.isPresent(), "指定项目不存在");
        SystemIndex index = o.get();
        Project project = o.get().getProject();
        project.setName(projectVo.getName());
        project.setDescribe(projectVo.getDescribe());
        index.setUpdateTime(new java.util.Date());
        index = systemRepository.save(o.get());
        return convertProject(index);
    }

    /**
     * SystemIndex 条目转换成 ProjectVo
     *
     * @param entity
     * @return
     */
    private ProjectVo convertProject(SystemIndex entity) {
        Assert.notNull(entity, "convert Project fail entity must not be null");
        Assert.notNull(entity.getProject(), "convert Project fail project must not be null");
        Assert.isTrue("project".equals(entity.getType()),
                "convert Project fail type must be 'project' ");

        ProjectVo projectVo = new ProjectVo();
        BeanUtils.copyProperties(entity, projectVo);
        BeanUtils.copyProperties(entity.getProject(), projectVo);
        fillProjectCreatorDisplayName(projectVo);
        fillProjectMemberCount(projectVo);
        return projectVo;
    }

    private void fillProjectMemberCount(ProjectVo projectVo) {
        if (projectVo == null || projectVo.getId() == null) {
            return;
        }

        List<SystemIndex> members = systemRepository.findByProjectMember_ProjectId(projectVo.getId(), PageRequest.of(0, 1000));
        projectVo.setMemberCount(members.size());
    }

    private void fillProjectCreatorDisplayName(ProjectVo projectVo) {
        if (projectVo == null || projectVo.getCreate() == null) {
            return;
        }

        Optional<SystemIndex> creatorIndex = systemRepository.findById(projectVo.getCreate());
        if (!creatorIndex.isPresent() || creatorIndex.get().getUser() == null) {
            return;
        }

        String nickName = creatorIndex.get().getUser().getNickName();
        String name = creatorIndex.get().getUser().getName();
        if (nickName != null && !nickName.trim().isEmpty()) {
            projectVo.setCreateDisplayName(nickName);
        } else if (name != null && !name.trim().isEmpty()) {
            projectVo.setCreateDisplayName(name);
        }
    }

}
