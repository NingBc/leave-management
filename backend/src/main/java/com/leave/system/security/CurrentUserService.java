package com.leave.system.security;

import com.leave.system.entity.SysUser;
import com.leave.system.exception.BusinessException;
import com.leave.system.mapper.SysUserMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the caller identity from the security context.
 *
 * <p>
 * Used by endpoints that accept a {@code userId} parameter: the parameter is
 * only honoured when the caller is an administrator, otherwise the caller may
 * only address their own data.
 */
@Component
public class CurrentUserService {

    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final SysUserMapper userMapper;

    public CurrentUserService(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public SysUser requireCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException("未登录或登录已失效");
        }
        SysUser user = userMapper.selectByUsername(authentication.getName());
        if (user == null) {
            throw new BusinessException("当前登录用户不存在");
        }
        return user;
    }

    public Long currentUserId() {
        return requireCurrentUser().getId();
    }

    public boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (ROLE_ADMIN.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolve the user a request targets.
     *
     * @param requestedUserId user id supplied by the client (may be null)
     * @return the requested id for administrators, the caller's own id otherwise
     * @throws AccessDeniedException if a non-admin targets somebody else
     */
    public Long resolveTargetUserId(Long requestedUserId) {
        Long selfId = currentUserId();
        if (requestedUserId == null || requestedUserId.equals(selfId)) {
            return selfId;
        }
        if (!isAdmin()) {
            throw new AccessDeniedException("无权查看或操作其他员工的年假数据");
        }
        return requestedUserId;
    }
}
