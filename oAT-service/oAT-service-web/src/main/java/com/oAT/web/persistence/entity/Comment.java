package com.oAT.web.persistence.entity;


import java.io.Serializable;
import java.util.Date;

public class Comment implements Serializable, StandardDate {
    private String userId;
    private Date time;
    private String content;
    private Replie replies[];

    // ============ getter/setter ============

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Date getTime() {
        return time;
    }

    public void setTime(Date time) {
        this.time = time;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Replie[] getReplies() {
        return replies;
    }

    public void setReplies(Replie[] replies) {
        this.replies = replies;
    }


    public static class Replie implements Serializable, StandardDate {
        private String userId;
        private String time;
        private String content;

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getTime() {
            return time;
        }

        public void setTime(String time) {
            this.time = time;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }
}
