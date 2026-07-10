package com.oAT.web.domain.usecase;

import com.oAT.web.common.DateUtil;
import com.oAT.web.esDao.entity.CaseCenterIndex;
import com.oAT.web.service.entity.UsecaseDetailVo;
import com.oAT.web.service.entity.UsecaseDirectoryVo;
import com.oAT.web.service.entity.UsecaseVo;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;

@Service
public class UsecaseViewMapper {

    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    public UsecaseDetailVo convertUsecaseDetail(CaseCenterIndex caseCenterIndex) {
        UsecaseDetailVo detailVo = new UsecaseDetailVo();
        BeanUtils.copyProperties(caseCenterIndex.getUsecase(), detailVo);
        detailVo.setId(caseCenterIndex.getId());
        detailVo.setCreateTime(caseCenterIndex.getCreateTime());
        detailVo.setUpdateTime(caseCenterIndex.getUpdateTime());
        return detailVo;
    }

    public UsecaseDirectoryVo convertDirectory(CaseCenterIndex index) {
        UsecaseDirectoryVo vo = new UsecaseDirectoryVo();
        BeanUtils.copyProperties(index.getDirectory(), vo);
        vo.setId(index.getId());
        vo.setUpdateTime(index.getUpdateTime());
        vo.setUpdateTimeText(formatDateTime(index.getUpdateTime()));
        vo.setUpdateTimeRelativeText(formatRelativeTime(index.getUpdateTime()));
        return vo;
    }

    public UsecaseVo convertUsecase(CaseCenterIndex caseCenterIndex) {
        UsecaseVo usecaseVo = new UsecaseVo();
        BeanUtils.copyProperties(caseCenterIndex.getUsecase(), usecaseVo);
        usecaseVo.setId(caseCenterIndex.getId());
        usecaseVo.setCreateTime(caseCenterIndex.getCreateTime());
        usecaseVo.setUpdateTime(caseCenterIndex.getUpdateTime());
        usecaseVo.setUpdateTimeText(formatDateTime(caseCenterIndex.getUpdateTime()));
        return usecaseVo;
    }

    private String formatDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return new SimpleDateFormat(DATE_TIME_PATTERN).format(date);
    }

    private String formatRelativeTime(Date date) {
        if (date == null) {
            return "-";
        }
        return DateUtil.timeDifference(date) + "前";
    }

}
