package com.oAT.web.service;

import com.oAT.web.service.entity.UsecaseImportResult;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public interface UsecaseFileService {

    void downloadTemplate(HttpServletResponse response) throws IOException;

    UsecaseImportResult importUsecases(String projectId, String operator, String currentDirectory, String appId, MultipartFile file) throws IOException;

    void exportUsecases(String projectId, String directory, String sort, String keyword, HttpServletResponse response) throws IOException;
}
