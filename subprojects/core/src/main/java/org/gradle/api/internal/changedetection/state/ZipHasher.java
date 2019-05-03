/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.changedetection.state;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import org.gradle.api.file.internal.FilePathUtil;
import org.gradle.internal.Factory;
import org.gradle.internal.FileUtils;
import org.gradle.internal.IoActions;
import org.gradle.internal.file.FileType;
import org.gradle.internal.fingerprint.FileSystemLocationFingerprint;
import org.gradle.internal.fingerprint.impl.DefaultFileSystemLocationFingerprint;
import org.gradle.internal.fingerprint.impl.NormalizedPathFingerprintCompareStrategy;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hasher;
import org.gradle.internal.hash.Hashing;
import org.gradle.internal.snapshot.RegularFileSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

public class ZipHasher implements RegularFileHasher, ConfigurableNormalizer {

    private static final Set<String> KNOWN_ZIP_EXTENSIONS = ImmutableSet.of("zip", "jar", "war", "ear", "apk");
    private static final Logger LOGGER = LoggerFactory.getLogger(ZipHasher.class);

    public static boolean isZipFile(final String name) {
        for (String extension : KNOWN_ZIP_EXTENSIONS) {
            if (FileUtils.hasExtensionIgnoresCase(name, extension)) {
                return true;
            }
        }
        return false;
    }

    private final ResourceHasher resourceHasher;
    private final ResourceFilter resourceFilter;

    public ZipHasher(ResourceHasher resourceHasher, ResourceFilter resourceFilter) {
        this.resourceHasher = resourceHasher;
        this.resourceFilter = resourceFilter;
    }

    @Nullable
    @Override
    public HashCode hash(RegularFileSnapshot fileSnapshot) {
        return hashZipContents(fileSnapshot);
    }

    @Override
    public void appendConfigurationToHasher(Hasher hasher) {
        hasher.putString(getClass().getName());
        resourceHasher.appendConfigurationToHasher(hasher);
        resourceFilter.appendConfigurationToHasher(hasher);
    }

    @Nullable
    private HashCode hashZipContents(RegularFileSnapshot zipFileSnapshot) {
        try {
            List<FileSystemLocationFingerprint> fingerprints = fingerprintZipEntries(zipFileSnapshot.getAbsolutePath());
            if (fingerprints.isEmpty()) {
                return null;
            }
            Hasher hasher = Hashing.newHasher();
            NormalizedPathFingerprintCompareStrategy.appendSortedToHasher(hasher, fingerprints);
            return hasher.hash();
        } catch (Exception e) {
            return hashMalformedZip(zipFileSnapshot, e);
        }
    }

    private List<FileSystemLocationFingerprint> fingerprintZipEntries(String zipFile) throws IOException {
        ZipInput input = null;
        try {
            input = ZipInput.create(new File(zipFile));
            return fingerprintZipEntries(input);
        } finally {
            IoActions.closeQuietly(input);
        }
    }

    private List<FileSystemLocationFingerprint> fingerprintZipEntries(ZipInput input) throws IOException {
        List<FileSystemLocationFingerprint> fingerprints = Lists.newArrayList();
        ZipEntry zipEntry;
        RelativePathFactory relativePathFactory = new RelativePathFactory();

        while ((zipEntry = input.getNextEntry()) != null) {
            relativePathFactory.setZipEntry(zipEntry);
            if (zipEntry.isDirectory() || resourceFilter.shouldBeIgnored(relativePathFactory)) {
                continue;
            }
            if (isZipFile(zipEntry.getName())) {
                fingerprints.addAll(fingerprintZipEntries(ZipInput.create(zipEntry.getInputStream())));
            } else {
                HashCode hash = resourceHasher.hash(zipEntry);
                if (hash != null) {
                    fingerprints.add(new DefaultFileSystemLocationFingerprint(zipEntry.getName(), FileType.RegularFile, hash));
                }
            }
        }

        return fingerprints;
    }

    private static class RelativePathFactory implements Factory<String[]> {

        private ZipEntry zipEntry;

        @Nullable
        @Override
        public String[] create() {
            return FilePathUtil.getPathSegments(zipEntry.getName());
        }

        public void setZipEntry(ZipEntry zipEntry) {
            this.zipEntry = zipEntry;
        }
    }

    private HashCode hashMalformedZip(RegularFileSnapshot zipFileSnapshot, Exception e) {
        LOGGER.debug("Malformed zip '{}'. Falling back to full content hash instead of entry hashing.", zipFileSnapshot.getName(), e);
        return zipFileSnapshot.getHash();
    }
}
