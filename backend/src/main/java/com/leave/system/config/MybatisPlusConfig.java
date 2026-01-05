package com.leave.system.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // Add Pagination Interceptor
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        // Note: InnerInterceptor interface might not be implemented by
        // SqlLogInterceptor directly if it implements
        // org.apache.ibatis.plugin.Interceptor
        // So we might need to register it differently or make SqlLogInterceptor
        // implement InnerInterceptor
        // However, standard MyBatis interceptors are added to the SqlSessionFactory,
        // not necessarily via MybatisPlusInterceptor
        // Let's check if we can add it as a bean directly and Spring Boot picks it up,
        // or add it to SqlSessionFactory.
        // Actually, for standard MyBatis Interceptor, just @Component is enough for
        // MyBatis-Spring-Boot-Starter to pick it up.
        // So we just need this config for Pagination.
        return interceptor;
    }
}
