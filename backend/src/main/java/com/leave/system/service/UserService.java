package com.leave.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leave.system.dto.ChangePasswordDTO;
import com.leave.system.dto.UserImportResult;
import com.leave.system.entity.SysUser;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface UserService {

    Page<SysUser> getUserPage(int current, int size);

    List<SysUser> getAllUsers();

    SysUser getById(Long id);

    SysUser getByUsername(String username);

    SysUser getByDingtalkUserId(String dingtalkUserId);

    void addUser(SysUser user);

    void updateUser(SysUser user);

    void changePassword(ChangePasswordDTO dto, String username);

    void deleteUser(Long id);

    void resignUser(Long id, Map<String, String> body);

    void activateUser(Long id);

    void importSingleUser(org.apache.commons.csv.CSVRecord record);

    UserImportResult importUsers(MultipartFile file);

    void calculateSeniority(SysUser user);

    LocalDate parseDate(String dateStr);
}
