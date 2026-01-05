package com.leave.system.aspect;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;

@Aspect
@Component
public class WebLogAspect {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WebLogAspect.class);

    @Pointcut("execution(public * com.leave.system.controller..*.*(..))")
    public void webLog() {
    }

    @Around("webLog()")
    public Object doAround(ProceedingJoinPoint proceedingJoinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();

        // Get request info
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes.getRequest();

        log.info("URL          : {}", request.getRequestURL().toString());
        log.info("Class Method : {}.{}", proceedingJoinPoint.getSignature().getDeclaringTypeName(),
                proceedingJoinPoint.getSignature().getName());
        log.info("IP           : {}", request.getRemoteAddr());
        log.info("Request Args : {}", Arrays.toString(proceedingJoinPoint.getArgs()));

        Object result = proceedingJoinPoint.proceed();

        long endTime = System.currentTimeMillis();
        log.info("Response     : {}", result);
        log.info("Time Taken   : {} ms", (endTime - startTime));

        return result;
    }
}
