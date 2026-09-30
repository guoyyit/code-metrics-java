package com.github.guoyyit.codemetricsjava.rules;

import org.springframework.util.AntPathMatcher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 将规则作用范围（ant 风格 glob）匹配到相对文件路径。
 */
@Component
public class ScopeMatcher {

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public boolean matches(String relativePath, List<String> include, List<String> exclude) {
        boolean included = include == null || include.isEmpty()
                || include.stream().anyMatch(pattern -> pathMatcher.match(pattern, relativePath));
        if (!included) {
            return false;
        }
        return exclude == null
                || exclude.stream().noneMatch(pattern -> pathMatcher.match(pattern, relativePath));
    }
}
