package com.zyh.archivemind.service;


import com.zyh.archivemind.exception.CustomException;
import com.zyh.archivemind.model.User;
import com.zyh.archivemind.repository.UserRepository;
import com.zyh.archivemind.utils.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * UserService 类用于处理用户注册和认证相关的业务逻辑。
 */
@Service
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);
    


    @Autowired
    private UserRepository userRepository;



    /**
     * 注册新用户。
     *
     * @param username 要注册的用户名
     * @param password 要注册的用户密码
     * @throws CustomException 如果用户名已存在，则抛出异常
     */
    @Transactional
    public void registerUser(String username, String password) {
        // 检查数据库中是否已存在该用户名
        if (userRepository.findByUsername(username).isPresent()) {
            // 若用户名已存在，抛出自定义异常，状态码为 400 Bad Request
            throw new CustomException("Username already exists", HttpStatus.BAD_REQUEST);
        }
        
        User user = new User();
        user.setUsername(username);
        // 对密码进行加密处理并设置到 User 对象中
        user.setPassword(PasswordUtil.encode(password));
        // 设置用户角色为普通用户
        user.setRole(User.Role.USER);
        
        // 保存用户以生成ID
        userRepository.save(user);
        
        userRepository.save(user);

        logger.info("User registered successfully with private organization tag: {}", username);
    }
    



    
    /**
     * 如果系统中没有管理员用户，则创建一个系统管理员
     */
    private User createSystemAdminIfNotExists() {
        String systemAdminUsername = "system_admin";
        
        return userRepository.findByUsername(systemAdminUsername)
                .orElseGet(() -> {
                    logger.info("Creating system admin user");
                    User systemAdmin = new User();
                    systemAdmin.setUsername(systemAdminUsername);
                    // 生成随机密码
                    String randomPassword = generateRandomPassword();
                    systemAdmin.setPassword(PasswordUtil.encode(randomPassword));
                    systemAdmin.setRole(User.Role.ADMIN);
                    
                    logger.info("System admin created with password: {}", randomPassword);
                    return userRepository.save(systemAdmin);
                });
    }
    
    /**
     * 生成随机密码
     */
    private String generateRandomPassword() {
        // 生成16位随机密码
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            int index = (int) (Math.random() * chars.length());
            sb.append(chars.charAt(index));
        }
        return sb.toString();
    }

    /**
     * 创建管理员用户。
     *
     * @param username 要注册的管理员用户名
     * @param password 要注册的管理员密码
     * @param creatorUsername 创建者的用户名（必须是已存在的管理员）
     * @throws CustomException 如果用户名已存在或创建者不是管理员，则抛出异常
     */
    public void createAdminUser(String username, String password, String creatorUsername) {
        // 验证创建者是否为管理员
        User creator = userRepository.findByUsername(creatorUsername)
                .orElseThrow(() -> new CustomException("Creator not found", HttpStatus.NOT_FOUND));
        
        if (creator.getRole() != User.Role.ADMIN) {
            throw new CustomException("Only administrators can create admin accounts", HttpStatus.FORBIDDEN);
        }
        
        // 检查数据库中是否已存在该用户名
        if (userRepository.findByUsername(username).isPresent()) {
            throw new CustomException("Username already exists", HttpStatus.BAD_REQUEST);
        }
        
        User adminUser = new User();
        adminUser.setUsername(username);
        adminUser.setPassword(PasswordUtil.encode(password));
        adminUser.setRole(User.Role.ADMIN);
        userRepository.save(adminUser);
    }

    /**
     * 对用户进行认证。
     *
     * @param username 要认证的用户名
     * @param password 要认证的用户密码
     * @return 认证成功后返回用户的用户名
     * @throws CustomException 如果用户名或密码无效，则抛出异常
     */
    public String authenticateUser(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("Invalid username or password", HttpStatus.UNAUTHORIZED));
        // 比较输入的密码和数据库中存储的加密密码是否匹配
        if (!PasswordUtil.matches(password, user.getPassword())) {
            // 若不匹配，抛出自定义异常，状态码为 401 Unauthorized
            throw new CustomException("Invalid username or password", HttpStatus.UNAUTHORIZED);
        }
        // 认证成功，返回用户的用户名
        return user.getUsername();
    }

    /**
     * 获取用户列表，支持分页和过滤
     * 
     * @param keyword 搜索关键词
     * @param orgTag 组织标签过滤
     * @param status 用户状态过滤
     * @param page 页码
     * @param size 每页大小
     * @return 用户列表数据
     */
    public Map<String, Object> getUserList(String keyword, String orgTag, Integer status, int page, int size) {
        // 页码从1开始，需要转换为从0开始
        int pageIndex = page > 0 ? page - 1 : 0;
        // 创建分页请求
        Pageable pageable = PageRequest.of(pageIndex, size, Sort.by("createdAt").descending());
        
        // 获取用户列表
        Page<User> userPage;
        
        if (orgTag != null && !orgTag.isEmpty()) {
            // 按组织标签过滤用户
            // 由于我们存储组织标签为逗号分隔的字符串，需要自定义实现
            // 这里简化处理，获取所有用户后手动过滤
            List<User> allUsers = userRepository.findAll();
            List<User> filteredUsers = allUsers.stream()
                    .filter(user -> {
                        // 过滤组织标签
                        if (user.getOrgTags() != null && !user.getOrgTags().isEmpty()) {
                            Set<String> userTags = new HashSet<>(Arrays.asList(user.getOrgTags().split(",")));
                            if (!userTags.contains(orgTag)) {
                                return false;
                            }
                        } else {
                            return false;
                        }
                        
                        // 过滤关键词
                        if (keyword != null && !keyword.isEmpty()) {
                            boolean matchesKeyword = user.getUsername().contains(keyword);
                            if (!matchesKeyword) {
                                return false;
                            }
                        }
                        
                        // 过滤状态
                        if (status != null) {
                            return user.getRole() == (status == 1 ? User.Role.USER : User.Role.ADMIN);
                        }
                        
                        return true;
                    })
                    .collect(Collectors.toList());
            
            // 手动分页
            int start = (int) pageable.getOffset();
            int end = Math.min((start + pageable.getPageSize()), filteredUsers.size());
            
            List<User> pageContent = start < end ? filteredUsers.subList(start, end) : Collections.emptyList();
            userPage = new PageImpl<>(pageContent, pageable, filteredUsers.size());
        } else {
            // 使用 JPA 分页查询（不含组织标签过滤）
            // 这里假设UserRepository有findByKeywordAndStatus方法，实际中可能需要自定义实现
            userPage = userRepository.findAll(pageable);
            
            // 手动过滤（简化实现）
            List<User> filteredUsers = userPage.getContent().stream()
                    .filter(user -> {
                        // 过滤关键词
                        if (keyword != null && !keyword.isEmpty()) {
                            boolean matchesKeyword = user.getUsername().contains(keyword);
                            if (!matchesKeyword) {
                                return false;
                            }
                        }
                        
                        // 过滤状态
                        if (status != null) {
                            return user.getRole() == (status == 1 ? User.Role.USER : User.Role.ADMIN);
                        }
                        
                        return true;
                    })
                    .collect(Collectors.toList());
                    
            userPage = new PageImpl<>(filteredUsers, pageable, filteredUsers.size());
        }
        
        // 转换为前端需要的格式
        List<Map<String, Object>> userList = userPage.getContent().stream()
                .map(user -> {
                    Map<String, Object> userMap = new HashMap<>();
                    userMap.put("userId", user.getId());
                    userMap.put("username", user.getUsername());
                    userMap.put("primaryOrg", user.getPrimaryOrg());
                    userMap.put("status", user.getRole() == User.Role.USER ? 1 : 0);
                    userMap.put("createdAt", user.getCreatedAt());
                    
                    return userMap;
                })
                .collect(Collectors.toList());
        
        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("content", userList);
        result.put("totalElements", userPage.getTotalElements());
        result.put("totalPages", userPage.getTotalPages());
        result.put("size", userPage.getSize());
        result.put("number", userPage.getNumber() + 1); // 转换为从1开始的页码
        
        return result;
    }
}

