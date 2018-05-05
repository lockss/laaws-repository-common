/*
 * Copyright (c) 2017, Board of Trustees of Leland Stanford Jr. University,
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

package org.lockss.laaws.rs.io.storage.hdfs;

import org.apache.hadoop.fs.Path;
import org.junit.jupiter.api.Test;
import org.lockss.laaws.rs.io.storage.hdfs.RollingUuidFileNamingStrategy;
import org.lockss.util.test.LockssTestCase5;
import org.springframework.data.hadoop.store.strategy.naming.FileNamingStrategy;
import org.springframework.data.hadoop.store.strategy.naming.UuidFileNamingStrategy;

import static org.junit.Assert.*;

/**
 * Test for RollingUuidFileNamingStrategy
 */
public class TestRollingUuidFileNamingStrategy extends LockssTestCase5 {
    private FileNamingStrategy namingStrategy = new RollingUuidFileNamingStrategy();

    @Test
    public void nextResolvePath() throws Exception {
        // Resolve a path
        Path path = namingStrategy.resolve(null);

        // Iterate to the next name in the strategy
        namingStrategy.next();

        // Check that the naming strategy resolves to something different
        assertNotEquals(path, namingStrategy.resolve(null));
    }

    @Test
    public void nextUuid() throws Exception {
        // Get the current UUID
        String uuid = ((UuidFileNamingStrategy)namingStrategy).getUuid();

        // Set strategy to the next UUID
        namingStrategy.next();

        // Assert the UUIDs are not equal
        assertNotEquals(uuid, ((UuidFileNamingStrategy)namingStrategy).getUuid());
    }

}