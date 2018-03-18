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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.lockss.laaws.rs.model.ArtifactIdentifier;
import org.lockss.laaws.rs.model.ArtifactData;
import org.lockss.laaws.rs.model.Artifact;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ArtifactData index implemented in memory, not persisted.
 */
public class VolatileArtifactIndex implements ArtifactIndex {
    private final static Log log = LogFactory.getLog(VolatileArtifactIndex.class);

    // Map from artifact ID to Artifact
    private Map<String, Artifact> index = new LinkedHashMap<>();

    /**
     * Adds an artifact to the index.
     * 
     * @param artifactData
     *          An ArtifactData with the artifact to be added to the index,.
     * @return an Artifact with the artifact indexing data.
     */
    @Override
    public Artifact indexArtifact(ArtifactData artifactData) {
        if (artifactData == null) {
          throw new IllegalArgumentException("Null artifact");
        }

        ArtifactIdentifier artifactId = artifactData.getIdentifier();

        if (artifactId == null) {
          throw new IllegalArgumentException("ArtifactData has null identifier");
        }

        String id = artifactId.getId();

        if (StringUtils.isEmpty(id)) {
          throw new IllegalArgumentException(
              "ArtifactIdentifier has null or empty id");
        }

        Artifact indexData = new Artifact(
                id,
                artifactId.getCollection(),
                artifactId.getAuid(),
                artifactId.getUri(),
                artifactId.getVersion(),
                false,
                artifactData.getStorageUrl()
        );

        index.put(id, indexData);

        return indexData;
    }

    /**
     * Provides the index data of an artifact with a given text index
     * identifier.
     * 
     * @param indexDataId
     *          A String with the artifact index identifier.
     * @return an Artifact with the artifact indexing data.
     */
    @Override
    public Artifact getArtifactIndexData(String indexDataId) {
        if (StringUtils.isEmpty(indexDataId)) {
          throw new IllegalArgumentException("Null or empty identifier");
        }
        return index.get(indexDataId);
    }

    /**
     * Provides the index data of an artifact with a given index identifier
     * UUID.
     * 
     * @param indexDataId
     *          An UUID with the artifact index identifier.
     * @return an Artifact with the artifact indexing data.
     */
    @Override
    public Artifact getArtifactIndexData(UUID indexDataId) {
        if (indexDataId == null) {
          throw new IllegalArgumentException("Null UUID");
        }
        return getArtifactIndexData(indexDataId.toString());
    }

    /**
     * Commits to the index an artifact with a given text index identifier.
     * 
     * @param artifactId
     *          A String with the artifact index identifier.
     * @return an Artifact with the committed artifact indexing data.
     */
    @Override
    public Artifact commitArtifact(String artifactId) {
        if (StringUtils.isEmpty(artifactId)) {
          throw new IllegalArgumentException("Null or empty identifier");
        }
        Artifact indexedData = index.get(artifactId);

        if (indexedData != null) {
          indexedData.setCommitted(true);
        }

        return indexedData;
    }

    /**
     * Commits to the index an artifact with a given index identifier UUID.
     * 
     * @param artifactId
     *          An UUID with the artifact index identifier.
     * @return an Artifact with the committed artifact indexing data.
     */
    @Override
    public Artifact commitArtifact(UUID artifactId) {
        if (artifactId == null) {
          throw new IllegalArgumentException("Null UUID");
        }
        return commitArtifact(artifactId.toString());
    }

    /**
     * Removes from the index an artifact with a given text index identifier.
     * 
     * @param artifactId
     *          A String with the artifact index identifier.
     * @return <code>true</code> if the artifact was removed from in the index,
     * <code>false</code> otherwise.
     */
    @Override
    public boolean deleteArtifact(String artifactId) {
      if (StringUtils.isEmpty(artifactId)) {
        throw new IllegalArgumentException("Null or empty identifier");
      }
      boolean result = false;

      synchronized (this) {
        if (index.remove(artifactId) != null) {
          result = index.get(artifactId) == null;
        }
      }

      return result;
    }

    /**
     * Removes from the index an artifact with a given index identifier UUID.
     * 
     * @param indexDataId
     *          A String with the artifact index identifier.
     * @return <code>true</code> if the artifact was removed from in the index,
     * <code>false</code> otherwise.
     */
    @Override
    public boolean deleteArtifact(UUID indexDataId) {
        if (indexDataId == null) {
          throw new IllegalArgumentException("Null UUID");
        }
        return deleteArtifact(indexDataId.toString());
    }

    /**
     * Provides an indication of whether an artifact with a given text index
     * identifier exists in the index.
     * 
     * @param artifactId
     *          A String with the artifact identifier.
     * @return <code>true</code> if the artifact exists in the index,
     * <code>false</code> otherwise.
     */
    @Override
    public boolean artifactExists(String artifactId) {
        if (StringUtils.isEmpty(artifactId)) {
          throw new IllegalArgumentException("Null or empty identifier");
        }
        return index.containsKey(artifactId);
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
        Stream<Artifact> artifactStream = index.values().stream();
        Stream<Artifact> committedArtifacts = artifactStream.filter(x -> x.getCommitted());
        Map<String, List<Artifact>> collections = committedArtifacts.collect(Collectors.groupingBy(Artifact::getCollection));

        return collections.keySet().iterator();
    }

    /**
     * Returns a list of Archival Unit IDs (AUIDs) in this LOCKSS repository collection.
     *
     * @param collection A {@code String} containing the LOCKSS repository collection ID.
     * @return A {@code Iterator<String>} iterating over the AUIDs in this LOCKSS repository collection.
     * @throws IOException
     */
    @Override
    public Iterator<String> getAuIds(String collection) throws IOException {
        return getCommittedArtifacts(collection).map(x -> x.getAuid()).iterator();
    }

    /**
     * Provides the committed artifacts in a collection that belong to an
     * Archival Unit.
     * 
     * @param collection
     *          A String with the collection identifier.
     * @param auid
     *          A String with the Archival Unit identifier.
     * @return an {@code Iterator<Artifact>} with the committed
     *         artifacts in the collection that belong to the Archival Unit.
     */
    @Override
    public Iterator<Artifact> getArtifactsInAU(String collection, String auid) {
        ArtifactPredicateBuilder query = new ArtifactPredicateBuilder();
        query.filterByCommitStatus(true);
        query.filterByCollection(collection);
        query.filterByAuid(auid);

        return index.values().stream().filter(query.build()).iterator();
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
     * @return an {@code Iterator<Artifact>} with the committed
     *         artifacts in the collection that belong to the Archival Unit and
     *         that contain a URL with the given prefix.
     */
    @Override
    public Iterator<Artifact> getArtifactsInAUWithURL(String collection, String auid, String prefix) {
        ArtifactPredicateBuilder query = new ArtifactPredicateBuilder();
        query.filterByCommitStatus(true);
        query.filterByCollection(collection);
        query.filterByAuid(auid);
        query.filterByURIPrefix(prefix);

        return index.values().stream().filter(query.build()).iterator();
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
     * @return an {@code Iterator<Artifact>} with the committed
     *         artifacts in the collection that belong to the Archival Unit and
     *         that contain an exact match of a URL.
     */
    @Override
    public Iterator<Artifact> getArtifactsInAUWithURLMatch(
	String collection, String auid, String url) {
      ArtifactPredicateBuilder query = new ArtifactPredicateBuilder();
      query.filterByCommitStatus(true);
      query.filterByCollection(collection);
      query.filterByAuid(auid);
      query.filterByURIMatch(url);

      return index.values().stream().filter(query.build()).iterator();
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
     * @return an {@code Iterator<Artifact>} with the committed
     *         artifacts in the collection that belong to the Archival Unit and
     *         that contain a URL with the given prefix and that match the given
     *         version.
     */
    @Override
    public Iterator<Artifact> getArtifactsInAUWithURL(
	String collection, String auid, String prefix, String version) {
      ArtifactPredicateBuilder query = new ArtifactPredicateBuilder();
      query.filterByCommitStatus(true);
      query.filterByCollection(collection);
      query.filterByAuid(auid);
      query.filterByURIPrefix(prefix);
      query.filterByVersion(version);

      return index.values().stream().filter(query.build()).iterator();

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
     * @return an {@code Iterator<Artifact>} with the committed
     *         artifacts in the collection that belong to the Archival Unit and
     *         that contain an exact match of a URL and that match the given
     *         version.
     */
    @Override
    public Iterator<Artifact> getArtifactsInAUWithURLMatch(
	String collection, String auid, String url, String version) {
      ArtifactPredicateBuilder query = new ArtifactPredicateBuilder();
      query.filterByCommitStatus(true);
      query.filterByCollection(collection);
      query.filterByAuid(auid);
      query.filterByURIMatch(url);
      query.filterByVersion(version);

      return index.values().stream().filter(query.build()).iterator();
    }

    /**
     * Provides the artifacts in the index that result from a given query.
     * 
     * @param query
     *          An {@code Iterator<Artifact>} with the query.
     * @return an {@code Iterator<Artifact>} with the artifacts
     *         resulting from the query.
     */
    public Iterator<Artifact> query(ArtifactPredicateBuilder query) {
        return index.values().stream().filter(query.build()).iterator();
    }

    /**
     * Provides the committed artifacts in a collection.
     * 
     * @param collection
     *          A String with the collection identifier.
     * @return a {@code Stream<Artifact>} with the committed artifacts
     *         in the collection.
     */
    private Stream<Artifact> getCommittedArtifacts(String collection) {
        ArtifactPredicateBuilder query = new ArtifactPredicateBuilder();
        query.filterByCommitStatus(true);
        query.filterByCollection(collection);

        return index.values().stream().filter(query.build());
    }
}
