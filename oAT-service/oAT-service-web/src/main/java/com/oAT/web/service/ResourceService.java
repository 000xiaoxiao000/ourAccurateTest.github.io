package com.oAT.web.service;

import java.io.File;

public interface ResourceService {

    File createCacheFile(String md5, String fileName);

    String getCachePath(String md5, String fileName);

    String getCacheRoot();

    String getGitCacheRoot();

    void checkAndCleanDiskSpace();
}
