package com.oAT.web.control.entity;


//TODO 异常统一拦截
public class ResultNotified<T> implements java.io.Serializable {
    private Boolean result;
    private String message;
    private String errorMessage;
    private String errorStack;
    private T data;

    public ResultNotified(Boolean result) {
        this.result = result;
    }

    public ResultNotified(Boolean result, String message, T data) {
        this.result = result;
        this.message = message;
        this.data = data;
    }

    public ResultNotified(Boolean result, String message) {
        this.result = result;
        this.message = message;
    }

    public Boolean getResult() {
        return result;
    }

    public void setResult(Boolean result) {
        this.result = result;
    }

    // New JSON-friendly accessor used by frontend JS (expects `success`)
    public Boolean getSuccess() {
        return this.result;
    }

    public void setSuccess(Boolean success) {
        this.result = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getErrorStack() {
        return errorStack;
    }

    public void setErrorStack(String errorStack) {
        this.errorStack = errorStack;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
