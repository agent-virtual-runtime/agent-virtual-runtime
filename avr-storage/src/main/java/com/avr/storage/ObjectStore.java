package com.avr.storage;

import java.util.List;
import java.util.Optional;

/** 可由 S3、OSS、COS 或 MinIO 适配器实现的最小对象存储契约。 */
public interface ObjectStore {
    /** 读取指定对象。 */
    Optional<byte[]> get(String key);

    /** 创建或覆盖指定对象。 */
    void put(String key, byte[] value);

    /** 删除指定对象。 */
    void delete(String key);

    /** 按 Key 前缀列出对象。 */
    List<String> list(String prefix);
}
