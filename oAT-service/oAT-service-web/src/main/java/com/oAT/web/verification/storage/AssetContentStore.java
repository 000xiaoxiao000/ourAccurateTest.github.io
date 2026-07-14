package com.oAT.web.verification.storage;

public interface AssetContentStore {
    String storageType();

    StoredContent store(StoreCommand command);

    String load(String storageKey);

    void delete(String storageKey);

    record StoreCommand(String projectId, String assetId, String contentHash, String content) {
    }

    record StoredContent(String storageType, String storageKey, long contentSize, String contentPreview) {
    }
}
