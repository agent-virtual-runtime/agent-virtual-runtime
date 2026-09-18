package com.avr.storage;

import com.avr.api.Artifact;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/** 为持久化 Workspace 编解码 Artifact 元数据和内容索引。 */
final class ArtifactPersistence {
    static final String INTERNAL_DIRECTORY = ".avr";
    static final String ARTIFACT_DIRECTORY = INTERNAL_DIRECTORY + "/artifacts";
    static final String MANIFEST_NAME = "manifest.properties";
    static final String CONTENT_DIRECTORY = "contents";

    private ArtifactPersistence() {
    }

    static byte[] encodeManifest(Artifact artifact) {
        Properties properties = new Properties();
        properties.setProperty("id", artifact.getId());
        properties.setProperty("root", artifact.getRoot());
        properties.setProperty("entrypoint", artifact.getEntrypoint());
        properties.setProperty("file.count", Integer.toString(artifact.getFiles().size()));

        for (int index = 0; index < artifact.getFiles().size(); index++) {
            properties.setProperty("file." + index, artifact.getFiles().get(index));
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            properties.store(output, "AVR artifact");
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot encode artifact manifest", exception);
        }
    }

    static Artifact decodeManifest(byte[] manifest, Function<String, String> contentReader) {
        Properties properties = new Properties();
        try (ByteArrayInputStream input = new ByteArrayInputStream(manifest)) {
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot decode artifact manifest", exception);
        }

        String id = required(properties, "id");
        String root = required(properties, "root");
        String entrypoint = required(properties, "entrypoint");
        int fileCount = parseFileCount(properties);
        List<String> files = new ArrayList<String>(fileCount);
        Map<String, String> contents = new LinkedHashMap<String, String>();

        for (int index = 0; index < fileCount; index++) {
            String path = required(properties, "file." + index);
            files.add(path);
            contents.put(path, contentReader.apply(path));
        }
        return new Artifact(id, root, entrypoint, files, contents);
    }

    static String encodePath(String path) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(path.getBytes(StandardCharsets.UTF_8));
    }

    static boolean isInternalPath(String virtualPath) {
        return virtualPath.equals("/" + INTERNAL_DIRECTORY)
                || virtualPath.startsWith("/" + INTERNAL_DIRECTORY + "/");
    }

    private static int parseFileCount(Properties properties) {
        String value = required(properties, "file.count");
        try {
            int count = Integer.parseInt(value);
            if (count < 0) {
                throw new IllegalArgumentException("negative file count");
            }
            return count;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("invalid artifact file count: " + value, exception);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("artifact manifest is missing " + key);
        }
        return value;
    }
}
