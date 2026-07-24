package com.oAT.web.control;

import com.oAT.web.common.EncryptUtil;
import com.oAT.web.control.entity.ResultNotified;
import com.oAT.web.service.ResourceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.util.DigestUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

@RestController
@RequestMapping("/api/resource")
public class ResourceControl {

    @Autowired
    ResourceService resourceService;

    @PostMapping("/upload")
    public ResultNotified<String> upload(@RequestParam("file") MultipartFile file,
                                         String md5) throws IOException {
        if (!StringUtils.hasText(md5)) {
            try (InputStream inputStream = file.getInputStream()) {
                md5 = DigestUtils.md5DigestAsHex(inputStream);
            }
        }
        if (md5.length() == 32) {
            md5 = EncryptUtil.md5_32To16(md5);
        }
        String fileName = file.getOriginalFilename();
        File cacheFile = resourceService.createCacheFile(md5, fileName);
        try {
            file.transferTo(cacheFile);
        } catch (Exception e) {
            throw new RuntimeException("文件上传失败:" + cacheFile.toURI().toString(), e);
        }
        return new ResultNotified<>(true, "上传成功", resourceService.getCachePath(md5, fileName));
    }

}
