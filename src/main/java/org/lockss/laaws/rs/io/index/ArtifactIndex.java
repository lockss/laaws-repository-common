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

package org.lockss.laaws.rs.io.index;

import org.lockss.laaws.rs.model.ArtifactData;
import org.lockss.laaws.rs.model.Artifact;

import java.io.IOException;
import java.util.Iterator;
import java.util.UUID;

/**
 * Interface of the artifact index.
 */
public interface ArtifactIndex {
    /**
     * Adds an artifact to the index.
     * 
     * @param artifactData
     *          An {@code ArtifactData} with the artifact to be indexed.
     * @return An {@code Artifact} with the artifact indexing data.
     */
    Artifact indexArtifact(ArtifactData artifactData) throws IOException;

    /**
     * Provides the index data of an artifact with a given text index
     * identifier.
     * 
     * @param artifactId
     *          A {@code String} with the artifact index identifier.
     * @return an Artifact with the artifact indexing data.
     */
    Artifact getArtifact(String artifactId) throws IOException;

    /**
     * Provides the index data of an artifact with a given index identifier
     * UUID.
     * 
     * @param artifactId
     *          An {@code UUID} with the artifact index identifier.
     * @return an Artifact with the artifact indexing data.
     */
    Artifact getArtifact(UUID artifactId) throws IOException;

    /**
     * Commits to the index an artifact with a given text index identifier.
     * 
     * @param artifactId
     *          A {@code String} with the artifact index identifier.
     * @return an Artifact with the committed artifact indexing data.
     */
    Artifact commitArtifact(String artifactId) throws IOException;

    /**
     * Commits to the index an artifact with a given index identifier UUID.
     * 
     * @param artifactId
     *          An {@code UUID} with the artifact index identifier.
     * @return an Artifact with the committed artifact indexing data.
     */
    Artifact commitArtifact(UUID artifactId) throws IOException;

    /**
     * Removes from the index an artifact with a given text index identifier.
     * 
     * @param artifactId
     *          A {@code String} with the artifact index identifier.
     * @return <code>true</code> if the artifact was removed from in the index,
     * <code>false</code> otherwise.
     */
    boolean deleteArtifact(String artifactId) throws IOException;

    /**
     * Removes from the index an artifact with a given index identifier UUID.
     * 
     * @param artifactId
     *          A String with the artifact index identifier.
     * @return <code>true</code> if the artifact was removed from in the index,
     * <code>false</code> otherwise.
     */
    boolean deleteArtifact(UUID artifactId) throws IOException;

    /**
     * Provides an indication of whether an artifact with a given text index
     * identifier exists in the index.
     * 
     * @param artifactId
     *          A String with the artifact identifier.
     * @return <code>true</code> if the artifact exists in the index,
     * <code>false</code> otherwise.
     */
    boolean artifactExists(String artifactId) throws IOException;

    /**
     * Provides the collection identifiers of the committed artifacts in the index.
     *
     * @return An {@code Iterator<String>} with the index committed artifacts
     * collection identifiers.
     */
    Iterator<String> getCollectionIds() throws IOException;

    /**
     * Returns a list of Archival Unit IDs (AUIDs) in this LOCKSS repository collection.
     *
     * @param collection
     *          A {@code String} containing the LOCKSS repository collection ID.
     * @return A {@code Iterator<String>} iterating over the AUIDs in this LOCKSS repository collection.
     * @throws IOException
     */
    Iterator<String> getAuIds(String collection) throws IOException;

    /**
     * Returns the artifacts of the latest committed version of all URLs, from a specified Archival Unit and collection.
     *
     * @param collection
     *          A {@code String} containing the collection ID.
     * @param auid
     *          A {@code String} containing the Archival Unit ID.
     * @return An {@code Iterator<Artifact>} containing the latest version of all URLs in an AU.
     * @throws IOException
     */
    Iterator<Artifact> getAllArtifacts(String collection,
                                       String auid)
        throws IOException;
    
    /**
     * Returns the artifacts of all committed versions of all URLs, from a specified Archival Unit and collection.
     *
     * @param collection
     *          A String with the collection identifier.
     * @param auid
     *          A String with the Archival Unit identifier.
     * @return An {@code Iterator<Artifact>} containing the committed artifacts of all version of all URLs in an AU.
     */
    Iterator<Artifact> getAllArtifactsAllVersions(String collection,
                                                  String auid)
        throws IOException;

    /**
     * Returns the artifacts of the latest committed versions of all URLs matching a prefix, from a specified Archival
     * Unit and collection.
     *
     * @param collection
     *          A {@code String} containing the collection ID.
     * @param auid
     *          A {@code String} containing the Archival Unit ID.
     * @param prefix
     *          A {@code String} containing a URL prefix.
     * @return An {@code Iterator<Artifact>} containing the latest version of all URLs matching a prefix in an AU.
     * @throws IOException
     */
    Iterator<Artifact> getAllArtifactsWithPrefix(String collection,
                                                 String auid,
                                                 String prefix)
        throws IOException;

    /**
     * Returns the artifacts of all committed versions of all URLs matching a prefix, from a specified Archival Unit and
     * collection.
     *
     * @param collection
     *          A String with the collection identifier.
     * @param auid
     *          A String with the Archival Unit identifier.
     * @param prefix
     *          A String with the URL prefix.
     * @return An {@code Iterator<Artifact>} containing the committed artifacts of all versions of all URLs matchign a
     *         prefix from an AU.
     */
    Iterator<Artifact> getAllArtifactsWithPrefixAllVersions(String collection,
                                                            String auid,
                                                            String prefix)
        throws IOException;

    /**
     * Returns the artifacts of all committed versions of a given URL, from a specified Archival Unit and collection.
     *
     * @param collection
     *          A {@code String} with the collection identifier.
     * @param auid
     *          A {@code String} with the Archival Unit identifier.
     * @param url
     *          A {@code String} with the URL to be matched.
     * @return An {@code Iterator<Artifact>} containing the committed artifacts of all versions of a given URL from an
     *         Archival Unit.
     */
    Iterator<Artifact> getArtifactAllVersions(String collection,
                                              String auid,
                                              String url)
        throws IOException;

    /**
     * Returns the artifact of the latest committed version of given URL, from a specified Archival Unit and collection.
     *
     * @param collection
     *          A {@code String} containing the collection ID.
     * @param auid
     *          A {@code String} containing the Archival Unit ID.
     * @param url
     *          A {@code String} containing a URL.
     * @return The {@code Artifact} representing the latest version of the URL in the AU.
     * @throws IOException
     */
    Artifact getArtifact(String collection,
                         String auid,
                         String url)
        throws IOException;

    /**
     * Returns the artifact of a given version of a URL, from a specified Archival Unit and collection.
     *
     * @param collection
     *          A String with the collection identifier.
     * @param auid
     *          A String with the Archival Unit identifier.
     * @param url
     *          A String with the URL to be matched.
     * @param version
     *          A String with the version.
     * @return The {@code Artifact} of a given version of a URL, from a specified AU and collection.
     */
    Artifact getArtifactVersion(String collection,
                                String auid,
                                String url,
                                Integer version)
        throws IOException;
}
