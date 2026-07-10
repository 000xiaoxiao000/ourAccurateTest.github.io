package com.oAT.web.service.entity;

import java.io.Serializable;

public class UserRegisterVo implements Serializable {

    private String name;
    private String nickname;
    private String email;
    private String password;
    private String againPassword;

    public String getAgainPassword() {
        return againPassword;
    }

    public void setAgainPassword(String againPassword) {
        this.againPassword = againPassword;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public String toString() {
        return "UserRegisterVo{" +
               "name='" + name + '\'' +
               ", nickname='" + nickname + '\'' +
               ", email='" + email + '\'' +
               ", password='" + password + '\'' +
               ", againPassword='" + againPassword + '\'' +
               '}';
    }
}
