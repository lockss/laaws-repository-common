/*
 * Copyright (c) 2017-2020, Board of Trustees of Leland Stanford Jr. University,
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

package org.lockss.laaws.rs.io.storage;

import org.lockss.laaws.rs.io.StorageInfoSource;
import org.lockss.laaws.rs.model.Artifact;
import org.lockss.laaws.rs.model.ArtifactData;
import org.lockss.laaws.rs.model.ArtifactIdentifier;
import org.lockss.laaws.rs.model.ArtifactRepositoryState;
import org.lockss.log.L4JLogger;
import org.lockss.util.lang.Ready;
import org.lockss.util.time.Deadline;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

/**
 * ArtifactData storage interface.
 *
 * @param <ID> extends {@code ArtifactIdentifier}
 *            Implementation of ArtifactIdentifier to parameterize this interface with.
 * @param <AD> extends {@code ArtifactData}
 *            Implementation of ArtifactData to parameterize this interfac with.
 * @param <MD> extends {@code RepositoryArtifactMetadata}
 *            Implementation of RepositoryArtifactMetadata to parameterize this interface with.
 */
// TODO Remove generics
public interface ArtifactDataStore<ID extends ArtifactIdentifier, AD extends ArtifactData, MD extends ArtifactRepositoryState> extends StorageInfoSource, Ready {
    /**
     *
     * @throws IOException
     */
    void initDataStore() throws IOException;

    void shutdownDataStore() throws InterruptedException;

    /**
     * Initializes a collection storage structure in an artifact data store implementation.
     *
     * @param collectionId
     *          A {@code String} containing the collection ID of the collection to initialize.
     */
    void initCollection(String collectionId) throws IOException;

    /**
     * Initializes an Archival Unit (AU) storage structure in an artifact data store implementation. Returns the AU's
     * initialized paths in storage or initializes a new one if none exist.
     *
     * @param collectionId A {@link String} containing the collection ID of this AU.
     * @param auid A {@link String} containing the AU ID of the AU to initialize.
     * @return A {@link List<Path>} containing all the initialized paths of the AU.
     * @throws IOException
     */
    List<Path> initAu(String collectionId, String auid) throws IOException;

    /**
     * Adds an artifact to this artifact store.
     *
     * Records an ArtifactData exactly as it has been received but does change its state. In particular, this method
     * will exhaust the ArtifactData's InputStream, computes the length, digest of its stream, and sets a storage URL.
     *
     * @param artifactData
     *          An {@code ArtifactData} to add to this artifact store.
     * @return Returns the {@code ArtifactData} as it is now recorded in this artifact store.
     * @throws NullPointerException
     *          if the given {@link ArtifactData} instance is null
     * @throws IOException
     */
    Artifact addArtifactData(AD artifactData) throws IOException;

    /**
     * Retrieves an artifact from this artifact data store.
     *
     * @param artifact
     *          An {@link Artifact} instance containing a reference to the
     *          artifact to retrieve from storage.
     * @return An {@link ArtifactData} instance retrieved from this artifact data
     *         store.
     * @throws IOException
     * @throws NullPointerException
     *          if the given {@link Artifact} instance is null
     */
    AD getArtifactData(Artifact artifact) throws IOException;

    /**
     * Commits an artifact to this artifact store.
     *
     * @param artifact
     *          An {@code Artifact} containing a reference to the artifact to update in storage.
     * @return A {@code RepositoryArtifactMetadata} representing the updated state of this artifact's repository metadata.
     * @throws IOException
     * @throws NullPointerException
     *          if the given {@link Artifact} instance is null
     */
    Future<Artifact> commitArtifactData(Artifact artifact) throws IOException;

    /**
     * Permanently removes an artifact from this artifact store.
     *
     * @param artifact
     *          An {@code Artifact} containing a reference to the artifact to remove from this artifact store.
     * @return A {@code RepositoryArtifactMetadata} with the final state of the removed artifact's repository metadata.
     * @throws IOException
     * @throws NullPointerException
     *          if the given {@link Artifact} instance is null
     */
    void deleteArtifactData(Artifact artifact) throws IOException;
    
    long DEFAULT_WAITREADY = 5000;

    @Override
    default void waitReady(Deadline deadline) throws TimeoutException {
        final L4JLogger log = L4JLogger.getLogger();

        while (!isReady()) {
            if (deadline.expired()) {
                throw new TimeoutException("Deadline for artifact data store to become ready expired");
            }

            long remainingTime = deadline.getRemainingTime();
            long sleepTime = Math.min(deadline.getSleepTime(), DEFAULT_WAITREADY);

            log.debug(
                "Waiting for artifact data store to become ready (retrying in {} ms; deadline in {} ms)",
                sleepTime,
                remainingTime
            );

            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                throw new RuntimeException("Interrupted while waiting for artifact data store to become ready");
            }
        }
    }
}
