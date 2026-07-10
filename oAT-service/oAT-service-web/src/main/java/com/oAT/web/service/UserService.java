package com.oAT.web.service;

import com.oAT.web.exceptions.UserOperationException;
import com.oAT.web.service.entity.UserRegisterVo;
import com.oAT.web.service.entity.UserVo;

import java.util.List;

public interface UserService {

    public void doRegister(UserRegisterVo register);

    public UserVo doLogin(String name, String email, String password) throws UserOperationException;

    UserVo getUser(String id);

    List<UserVo> getUsers(String... ids);

    List<UserVo> getAllUser();

    void updateUser(UserVo userVo);

    void changePassword(String userId, String oldPassword, String newPassword) throws UserOperationException;

}
