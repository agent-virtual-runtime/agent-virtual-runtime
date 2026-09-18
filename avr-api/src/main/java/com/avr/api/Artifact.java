package com.avr.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** 带入口文件的不可变工作空间产物快照。 */
public final class Artifact {
    private final String id;
    private final String root;
    private final String entrypoint;
    private final List<String> files;
    private final Map<String, String> contents;

    public Artifact(String id, String root, String entrypoint, List<String> files) {
        this(id, root, entrypoint, files, Collections.<String, String>emptyMap());
    }

    public Artifact(String id, String root, String entrypoint, List<String> files,
                    Map<String, String> contents) {
        this.id = id;
        this.root = root;
        this.entrypoint = entrypoint;
        this.files = Collections.unmodifiableList(new ArrayList<String>(files));
        this.contents = Collections.unmodifiableMap(new LinkedHashMap<String, String>(contents));
    }

    public String getId() {
        return id;
    }

    public String getRoot() {
        return root;
    }

    public String getEntrypoint() {
        return entrypoint;
    }

    public List<String> getFiles() {
        return files;
    }

    /** 从不可变快照中读取指定文本文件。 */
    public String readText(String path) {
        String content = contents.get(path);
        if (content == null) {
            throw new IllegalArgumentException("artifact file does not exist: " + path);
        }
        return content;
    }

    public Map<String, String> getContents() {
        return contents;
    }
}
