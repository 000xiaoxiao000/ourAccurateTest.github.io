package com.oAT.web.service;

import com.oAT.web.esDao.entity.LabelGroup;
import com.oAT.web.exceptions.UserOperationException;
import com.oAT.web.service.entity.LabelGroupVo;
import com.oAT.web.service.entity.LableType;
import com.oAT.web.service.entity.ProjectMemberVo;
import com.oAT.web.service.entity.ProjectVo;

import java.util.List;

public interface ProjectService {

    ProjectVo getProjectByProjectIdAndMemberId(String projectId, String memberId);


    ProjectVo getProject(String projectId);

    void deleteProject(String projectId, String userId, String password) throws UserOperationException;

    List<LabelGroup.Label> getLables(String projectId, LableType type);
    List<LabelGroup.Label> getLables(String projectId, LableType type, String... label);


    void deleteProjectMember(String projectId, String projectMemberId);

    void updateProjectMemberRole(String projectId, String projectMemberId, ProjectMemberVo.Role role);

    ProjectVo updateProject(ProjectVo projectVo);


    /**
     * 创建新项目
     */
    class CreateProjectParam {
        public CreateProjectParam(String name, String describe, String userId) {
            this.name = name;
            this.describe = describe;
            this.userId = userId;
        }

        public String name; // 名称
        public String describe; // 描述
        public String userId; // 用户id
        public String userName; // 用户名称
    }

    /**
     * 创建项目
     *
     * @param param
     * @return
     */
    ProjectVo createProject(CreateProjectParam param);

    /**
     * 查找用户所有项目列表
     *
     * @param memberId
     * @return
     */
    List<ProjectVo> findProjectByMemberId(String memberId);

    /**
     * 保存标签组
     * @param group
     */
    LabelGroupVo doSaveLabelGroup(LabelGroupVo group);

    /**
     *  获取标签组
     */
    List<LabelGroupVo> getLableGroup(String projectId, LableType type);

    ProjectMemberVo addProjectMember(String projectId, String userId);

    List<ProjectMemberVo> getProjectMembers(String projectId);

}
