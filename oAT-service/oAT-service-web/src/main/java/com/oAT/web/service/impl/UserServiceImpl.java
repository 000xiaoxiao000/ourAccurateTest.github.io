package com.oAT.web.service.impl;

import com.oAT.web.esDao.SystemRepository;
import com.oAT.web.esDao.entity.SystemIndex;
import com.oAT.web.esDao.entity.User;
import com.oAT.web.exceptions.DirtyDataException;
import com.oAT.web.exceptions.UserOperationException;
import com.oAT.web.service.UserService;
import com.oAT.web.service.entity.UserRegisterVo;
import com.oAT.web.service.entity.UserVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Service
public class UserServiceImpl implements UserService{

    private final static Logger logger = LoggerFactory.getLogger(UserServiceImpl.class);

    @Autowired
    private SystemRepository systemRepository;

    @Override
    public void doRegister(UserRegisterVo register) {
        Assert.notNull(register, "register must not be null");
        Assert.hasText(register.getName(), "用户名不能为空");
        Assert.hasText(register.getEmail(), "邮箱不能为空");
        Assert.hasText(register.getPassword(), "密码不能为空");
        Assert.isTrue(register.getPassword().equals(register.getAgainPassword()), "两次密码输入不一致");

        String name = register.getName().trim();
        String email = register.getEmail().trim();
        Assert.isTrue(systemRepository.findByUserNameOrUserEmail(name, email).isEmpty(), "用户名或邮箱已存在");

        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setPassword(DigestUtils
                .md5DigestAsHex(register.getPassword()
                        .getBytes(Charset.forName("UTF-8"))));
        user.setNickName(StringUtils.hasText(register.getNickname()) ? register.getNickname().trim() : name);
        SystemIndex systemIndex = new SystemIndex(user);
        systemRepository.save(systemIndex);
        logger.info("register succeed！： {} ", register);
    }

    @Override
    public UserVo doLogin(String name, String email, String password) throws UserOperationException {
        List<SystemIndex> list = systemRepository.findByUserNameOrUserEmail(name, email);
        Assert.isTrue(name != null || email != null, "params 'name' and 'email' At least one is not empty");
        if (list.isEmpty()) {
            throw new UserOperationException("指定用户不存在");
        } else if (list.size() > 1) {
            throw new DirtyDataException(String.format("存在多个相同账户!!! name=%S,email=%S", name, email));
        }
        User user = list.get(0).getUser();
        String md5Pwd = DigestUtils.md5DigestAsHex(password.getBytes(Charset.forName("UTF-8")));
        if (!user.getPassword().equals(md5Pwd)) {
            throw new UserOperationException("密码输入有误!");
        }
        UserVo userVo = new UserVo();
        // 拷贝基本属性
        BeanUtils.copyProperties(list.get(0), userVo);
        // 拷贝用户属性
        BeanUtils.copyProperties(user, userVo);
        return userVo;
    }

    @Override
    public UserVo getUser(String id) {
        Optional<SystemIndex> optional = systemRepository.findById(id);
        Assert.isTrue(optional.isPresent(), "not fount user by id=" + id);
        SystemIndex index = optional.get();
        UserVo userVo = convertUser(index);
        return userVo;
    }

    @Override
    public List<UserVo> getUsers(String... ids) {
        Iterable<SystemIndex> users = systemRepository.findAllById(Arrays.asList(ids));
        return StreamSupport.stream(users.spliterator(), false).map(a -> convertUser(a)).collect(Collectors.toList());
    }

    @Override
    public List<UserVo> getAllUser() {
        List<UserVo> result = new ArrayList<>();
        List<SystemIndex> list = systemRepository.findByType("user", PageRequest.of(0, 1000));
        for (SystemIndex index : list) {
            result.add(convertUser(index));
        }
        return result;
    }

    @Override
    public void updateUser(UserVo userVo) {
        Optional<SystemIndex> o = systemRepository.findById(userVo.getId());
        Assert.isTrue(o.isPresent(), "指定用户不存在 id=" + userVo.getId());
        SystemIndex index = o.get();
        BeanUtils.copyProperties(userVo, index.getUser(), "password", "header");
        index.setUpdateTime(new java.util.Date());
        systemRepository.save(index);
    }

    @Override
    public void changePassword(String userId, String oldPassword, String newPassword) throws UserOperationException {
        Assert.hasText(userId, "userId 不能为空");
        Assert.hasText(oldPassword, "oldPassword 不能为空");
        Assert.hasText(newPassword, "newPassword 不能为空");
        Optional<SystemIndex> o = systemRepository.findById(userId);
        Assert.isTrue(o.isPresent(), "指定用户不存在 id=" + userId);
        SystemIndex index = o.get();
        if (!index.getUser().getPassword().equals
                (DigestUtils.md5DigestAsHex(oldPassword.getBytes(Charset.forName("UTF-8"))))) {
            throw new UserOperationException("密码输入有误!");
        }
        index.getUser().setPassword(DigestUtils.
                md5DigestAsHex(newPassword.getBytes(Charset.forName("UTF-8"))));
        index.setUpdateTime(new java.util.Date());
        systemRepository.save(index);
    }

    private UserVo convertUser(SystemIndex index) {
        UserVo userVo = new UserVo();
        // 拷贝基本属性
        userVo.setId(index.getId());
        userVo.setUpdateTime(index.getUpdateTime());
        userVo.setCreateTime(index.getCreateTime());
        BeanUtils.copyProperties(index.getUser(), userVo);
        return userVo;
    }

}
