package com.github.guoyyit.codemetricsjava.engine;

/**
 * 待分析源文件的不可变描述。path 为相对根目录的 POSIX 风格路径。
 */
public class SourceFile {
    public final String path;
    public final String absolutePath;
    public final long size;
    public final String sha256;
    public final String content;

    public SourceFile(String path, String absolutePath, long size, String sha256, String content) {
        this.path = path;
        this.absolutePath = absolutePath;
        this.size = size;
        this.sha256 = sha256;
        this.content = content;
    }
}
