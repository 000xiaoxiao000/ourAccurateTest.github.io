package com.oAT.web.domain.version;

import com.oAT.web.persistence.entity.VersionCenterIndex;
import com.oAT.web.service.ResourceService;
import com.oAT.web.service.entity.VersionItemVo;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;

@Service
public class VersionItemViewService {

    @Autowired
    private ResourceService resourceService;

    public VersionItemVo convertVersionItem(VersionCenterIndex index) {
        VersionItemVo vo = new VersionItemVo();
        BeanUtils.copyProperties(index.getVersionItem(), vo);
        vo.setId(index.getId());
        populateFileState(vo);
        vo.setCreateTime(index.getCreateTime());
        return vo;
    }

    private void populateFileState(VersionItemVo vo) {
        if (!StringUtils.hasText(vo.getProgramFile())) {
            return;
        }
        vo.setProgramName(new File(vo.getProgramFile()).getName());
        File file = new File(vo.getProgramFile());
        if (!file.exists()) {
            file = new File(resourceService.getCacheRoot(), vo.getProgramFile());
        }
        vo.setFileExist(file.exists());
    }

}
