package com.evops;

import org.apache.shiro.spring.boot.autoconfigure.ShiroAnnotationProcessorAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// 排除 Shiro 注解处理器自动配置：它注册的 DefaultAdvisorAutoProxyCreator
// 默认使用 JDK 动态代理，会把 @Transactional 服务包成接口代理导致无法按类注入；
// 本项目只使用 Shiro 过滤链鉴权，不使用 @RequiresPermissions 等注解。
@EnableScheduling
@SpringBootApplication(exclude = ShiroAnnotationProcessorAutoConfiguration.class)
@MapperScan("com.evops.mapper")
public class EvopsApplication {
    public static void main(String[] args) {
        SpringApplication.run(EvopsApplication.class, args);
    }
}
