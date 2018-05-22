/*
 * Copyright (c) 2017-2018, Board of Trustees of Leland Stanford Jr. University,
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.lockss.laaws.rs.core;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.util.FileSystemUtils;
import java.io.File;
import java.io.IOException;

/**
 * Test class for {@code org.lockss.laaws.rs.core.LocalLockssRepository}
 */
public class TestLocalLockssRepository extends AbstractLockssRepositoryTest {
    private final static Log log = LogFactory.getLog(TestLocalLockssRepository.class);

    // The local repository root directory.
    private File repoBaseDir = null;

    protected File makeTempDir() throws IOException {
        File tmpFile = File.createTempFile("TestLocalLockssRepository", null, null);
        File tmpDir = new File(tmpFile.getPath() + ".d");
        tmpDir.mkdir();
        tmpFile.delete();
        return tmpDir;
    }

    @Override
    public LockssRepository makeLockssRepository() throws Exception {
        repoBaseDir = makeTempDir();
        return new LocalLockssRepository(repoBaseDir, (String)null);
    }

    /**
     * Run after the test is finished.
     */
    @Override
    public void tearDownArtifactDataStore() throws Exception {
        // Clean up the local repository directory tree used in the test.
        if (!FileSystemUtils.deleteRecursively(repoBaseDir)) {
          log.warn("Failed to delete temporary directory " + repoBaseDir);
        }

        super.tearDownArtifactDataStore();
    }
}
