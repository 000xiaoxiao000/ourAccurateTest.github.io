package com.oAT.web.service.entity;

public class UsecaseImportError {
    private int rowNumber;
    private String field;
    private String message;
    private String rawValue;

    public UsecaseImportError() {
    }

    public UsecaseImportError(int rowNumber, String field, String message, String rawValue) {
        this.rowNumber = rowNumber;
        this.field = field;
        this.message = message;
        this.rawValue = rawValue;
    }

    public int getRowNumber() {
        return rowNumber;
    }

    public void setRowNumber(int rowNumber) {
        this.rowNumber = rowNumber;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getRawValue() {
        return rawValue;
    }

    public void setRawValue(String rawValue) {
        this.rawValue = rawValue;
    }
}
