package com.oAT.web.api.account;


public final class AccountApiPayloads {

    private AccountApiPayloads() {
    }

    public static class UpdateProfileRequest {
        private String name;
        private String nickname;
        private String email;
        private String phone;
        private String readme;

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

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getReadme() {
            return readme;
        }

        public void setReadme(String readme) {
            this.readme = readme;
        }
    }

    public static class UpdatePasswordRequest {
        private String oldPassword;
        private String newPassword;
        private String newPasswordConfirm;

        public String getOldPassword() {
            return oldPassword;
        }

        public void setOldPassword(String oldPassword) {
            this.oldPassword = oldPassword;
        }

        public String getNewPassword() {
            return newPassword;
        }

        public void setNewPassword(String newPassword) {
            this.newPassword = newPassword;
        }

        public String getNewPasswordConfirm() {
            return newPasswordConfirm;
        }

        public void setNewPasswordConfirm(String newPasswordConfirm) {
            this.newPasswordConfirm = newPasswordConfirm;
        }
    }
}
