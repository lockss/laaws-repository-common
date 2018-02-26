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

package org.lockss.laaws.rs.core;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.lockss.laaws.rs.io.index.ArtifactIndex;
import org.lockss.laaws.rs.io.index.VolatileArtifactIndex;
import org.lockss.laaws.rs.io.storage.ArtifactStore;
import org.lockss.laaws.rs.io.storage.warc.VolatileWarcArtifactStore;
import org.lockss.laaws.rs.model.Artifact;
import org.lockss.laaws.rs.model.ArtifactIndexData;
import org.lockss.laaws.rs.model.RepositoryArtifactMetadata;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Base implementation of the LOCKSS Repository service.
 */
public class BaseLockssRepository implements LockssRepository {
    private final static Log log = LogFactory.getLog(BaseLockssRepository.class);

    private ArtifactStore store = null;
    private ArtifactIndex index = null;

    /**
     * Constructor. By default, we spin up a volatile in-memory LOCKSS repository.
     */
    public BaseLockssRepository() {
        this(new VolatileArtifactIndex(), new VolatileWarcArtifactStore());
    }

    /**
     * Configures this LOCKSS repository with the provided Artifact index and storage layers.
     *
     *
     *
     * @param index
     *          An instance of {@code ArtifactIndex}.
     * @param store
     *          An instance of {@code ArtifactStore}.
     */
    public BaseLockssRepository(ArtifactIndex index, ArtifactStore store) {
        this.index = index;
        this.store = store;
    }

    /**
     * Adds an artifact to the LOCKSS repository.
     *
     * @param artifact
     *          {@code Artifact} instance to add to the LOCKSS repository.
     * @return The artifact ID of the newly added artifact.
     * @throws IOException
     */
    @Override
    public String addArtifact(Artifact artifact) throws IOException {
        Artifact a = store.addArtifact(artifact);
        ArtifactIndexData indexData = index.indexArtifact(a);
        return indexData.getId();
    }

    /**
     * Retrieves an artifact from the LOCKSS repository.
     *
     * @param artifactId
     *          A String with the Artifact ID of the artifact to retrieve from the repository.
     * @return An {@code Artifact} referenced by this artifact ID.
     * @throws IOException
     */
    @Override
    public Artifact getArtifact(String collection, String artifactId) throws IOException {
        try {
            ArtifactIndexData data = index.getArtifactIndexData(artifactId);
            if (data == null)
                return null;

            return store.getArtifact(index.getArtifactIndexData(artifactId));
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Commits an artifact to the LOCKSS repository for permanent storage and inclusion in LOCKSS repository queries.
     *
     * @param artifactId A String with the Artifact ID of the artifact to commit to the repository.
     * @return TODO
     * @throws IOException
     */
    @Override
    public ArtifactIndexData commitArtifact(String collection, String artifactId) throws IOException {
        // Get artifact as it is currently
        ArtifactIndexData indexData = index.getArtifactIndexData(artifactId);
        Artifact artifact = null;

//        try {
//            artifact = store.getArtifact(indexData);
//        } catch (URISyntaxException e) {
//            e.printStackTrace();
//        }

        // Record the changed status in store
        store.updateArtifactMetadata(indexData.getIdentifier(), new RepositoryArtifactMetadata(
                indexData.getIdentifier(),
                true,
                false
        ));

        // Update the commit status in index
        return index.commitArtifact(artifactId);
    }

    /**
     * Permanently removes an artifact from the LOCKSS repository.
     *
     * @param artifactId
     *          A String with the Artifact ID of the artifact to remove from the LOCKSS repository.
     * @throws IOException
     */
    @Override
    public void deleteArtifact(String collection, String artifactId) throws IOException {
        try {
            store.deleteArtifact(index.getArtifactIndexData(artifactId));
            index.deleteArtifact(artifactId);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }

    /**
     * Returns a boolean indicating whether an artifact by an artifact ID exists in this LOCKSS repository.
     *
     * @param artifactId
     *          A String with the Artifact ID of the artifact to check for existence.
     * @return A boolean indicating whether an artifact exists in this repository.
     */
    @Override
    public boolean artifactExists(String artifactId) {
        return index.artifactExists(artifactId);
    }

    /**
     * Returns a boolean indicating whether an artifact is committed in this LOCKSS repository.
     *
     * @param artifactId
     *          Artifact ID of the artifact to check committed status.
     * @return A boolean indicating whether the artifact is committed.
     */
    @Override
    public boolean isArtifactCommitted(String artifactId) {
        ArtifactIndexData data = index.getArtifactIndexData(artifactId);
        return data.getCommitted();
    }

    /**
     * Provides the collection identifiers of the committed artifacts in the
     * index.
     *
     * @return an {@code Iterator<String>} with the index committed artifacts
     * collection identifiers.
     */
    @Override
    public Iterator<String> getCollectionIds() {
        return index.getCollectionIds();
    }

    /**
     * Provides the committed artifacts in a collection grouped by the
     * identifier of the Archival Unit to which they belong.
     *
     * @param collection A String with the collection identifier.
     * @return a {@code Map<String, List<ArtifactIndexData>>} with the committed
     * artifacts in the collection grouped by the identifier of the
     * Archival Unit to which they belong.
     */
    @Override
    public Map<String, List<ArtifactIndexData>> getAus(String collection) {
        return index.getAus(collection);
    }

    /**
     * Provides the committed artifacts in a collection that belong to an
     * Archival Unit.
     *
     * @param collection
     *          A String with the collection identifier.
     * @param auid
     *          A String with the Archival Unit identifier.
     * @return an {@code Iterator<ArtifactIndexData>} with the committed
     *         artifacts in the collection that belong to the Archival Unit.
     */
    @Override
    public Iterator<ArtifactIndexData> getArtifactsInAU(String collection, String auid) {
        return index.getArtifactsInAU(collection, auid);
    }

    /**
     * Provides the committed artifacts in a collection that belong to an
     * Archival Unit and that contain a URL with a given prefix.
     *
     * @param collection
     *          A String with the collection identifier.
     * @param auid
     *          A String with the Archival Unit identifier.
     * @param prefix
     *          A String with the URL prefix.
     * @return an {@code Iterator<ArtifactIndexData>} with the committed
     *         artifacts in the collection that belong to the Archival Unit and
     *         that contain a URL with the given prefix.
     */
    @Override
    public Iterator<ArtifactIndexData> getArtifactsInAUWithURL(String collection, String auid, String prefix) {
        return index.getArtifactsInAUWithURL(collection, auid, prefix);
    }

    /**
     * Provides the committed artifacts in a collection that belong to an
     * Archival Unit and that contain an exact match of a URL.
     *
     * @param collection
     *          A String with the collection identifier.
     * @param auid
     *          A String with the Archival Unit identifier.
     * @param url
     *          A String with the URL to be matched.
     * @return an {@code Iterator<ArtifactIndexData>} with the committed
     *         artifacts in the collection that belong to the Archival Unit and
     *         that contain an exact match of a URL.
     */
    @Override
    public Iterator<ArtifactIndexData> getArtifactsInAUWithURLMatch(String collection, String auid, String url) {
        return index.getArtifactsInAUWithURLMatch(collection, auid, url);
    }

    /**
     * Provides the committed artifacts in a collection that belong to an
     * Archival Unit and that contain a URL with a given prefix and that match a
     * given version.
     *
     * @param collection
     *          A String with the collection identifier.
     * @param auid
     *          A String with the Archival Unit identifier.
     * @param prefix
     *          A String with the URL prefix.
     * @param version
     *          A String with the version.
     * @return an {@code Iterator<ArtifactIndexData>} with the committed
     *         artifacts in the collection that belong to the Archival Unit and
     *         that contain a URL with the given prefix and that match the given
     *         version.
     */
    @Override
    public Iterator<ArtifactIndexData> getArtifactsInAUWithURL(String collection, String auid, String prefix, String version) {
        return index.getArtifactsInAUWithURL(collection, auid, prefix, version);
    }

    /**
     * Provides the committed artifacts in a collection that belong to an
     * Archival Unit and that contain an exact match of a URL and that match a
     * given version.
     *
     * @param collection
     *          A String with the collection identifier.
     * @param auid
     *          A String with the Archival Unit identifier.
     * @param url
     *          A String with the URL to be matched.
     * @param version
     *          A String with the version.
     * @return an {@code Iterator<ArtifactIndexData>} with the committed
     *         artifacts in the collection that belong to the Archival Unit and
     *         that contain an exact match of a URL and that match the given
     *         version.
     */
    @Override
    public Iterator<ArtifactIndexData> getArtifactsInAUWithURLMatch(String collection, String auid, String url, String version) {
        return index.getArtifactsInAUWithURLMatch(collection, auid, url, version);
    }
}