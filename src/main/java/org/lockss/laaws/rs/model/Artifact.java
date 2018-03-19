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

package org.lockss.laaws.rs.model;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.beans.Field;

/**
 * Data associated with an artifact in the index.
 */
public class Artifact {
    private static final Log log = LogFactory.getLog(Artifact.class);

    @Field("id")
    private String id;

    @Field("collection")
    private String collection;

    @Field("auid")
    private String auid;

    @Field("uri")
    private String uri;

    @Field("version")
    private Integer version;

    @Field("committed")
    private Boolean committed;

    @Field("storageUrl")
    private String storageUrl;

    /**
     * Constructor. Needed by Solrj for getBeans() support.
     *
     * TODO: Reconcile difference with constructor below, which checks parameters for illegal arguments.
     */
    public Artifact() {
        // Intentionally left blank
    }

    public Artifact(String id, String collection, String auid, String uri, Integer version, Boolean committed,
                             String storageUrl) {
        if (StringUtils.isEmpty(id)) {
          throw new IllegalArgumentException(
              "Cannot create Artifact with null or empty id");
        }
        this.id = id;

        if (StringUtils.isEmpty(collection)) {
          throw new IllegalArgumentException(
              "Cannot create Artifact with null or empty collection");
        }
        this.collection = collection;

        if (StringUtils.isEmpty(auid)) {
          throw new IllegalArgumentException(
              "Cannot create Artifact with null or empty auid");
        }
        this.auid = auid;

        if (StringUtils.isEmpty(uri)) {
          throw new IllegalArgumentException(
              "Cannot create Artifact with null or empty URI");
        }
        this.uri = uri;

        if (version == null) {
          throw new IllegalArgumentException(
              "Cannot create Artifact with null version");
        }
        this.version = version;

        if (committed == null) {
          throw new IllegalArgumentException(
              "Cannot create Artifact with null commit status");
        }
        this.committed = committed;

        if (StringUtils.isEmpty(storageUrl)) {
          throw new IllegalArgumentException("Cannot create "
              + "Artifact with null or empty storageUrl");
        }
        this.storageUrl = storageUrl;
    }

    public ArtifactIdentifier getIdentifier() {
        return new ArtifactIdentifier(id, collection, auid, uri, version);
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        if (StringUtils.isEmpty(collection)) {
          throw new IllegalArgumentException(
              "Cannot set null or empty collection");
        }
        this.collection = collection;
    }

    public String getAuid() {
        return auid;
    }

    public void setAuid(String auid) {
        if (StringUtils.isEmpty(auid)) {
          throw new IllegalArgumentException("Cannot set null or empty auid");
        }
        this.auid = auid;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        if (StringUtils.isEmpty(uri)) {
          throw new IllegalArgumentException("Cannot set null or empty URI");
        }
        this.uri = uri;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
//        if (StringUtils.isEmpty(version)) {
//          throw new IllegalArgumentException(
//              "Cannot set null or empty version");
//        }
        if (version == null) {
            throw new IllegalArgumentException("Cannot set null version");
        }

        this.version = version;
    }

    public String getId() {
        return id;
    }

    public Boolean getCommitted() {
        return committed;
    }

    public void setCommitted(Boolean committed) {
        if (committed == null) {
          throw new IllegalArgumentException("Cannot set null commit status");
        }
        this.committed = committed;
    }

    public String getStorageUrl() {
        return storageUrl;
    }

    public void setStorageUrl(String storageUrl) {
        if (StringUtils.isEmpty(storageUrl)) {
          throw new IllegalArgumentException(
              "Cannot set null or empty storageUrl");
        }
        this.storageUrl = storageUrl;
    }

    @Override
    public String toString() {
        return "Artifact{" +
                "id='" + id + '\'' +
                ", collection='" + collection + '\'' +
                ", auid='" + auid + '\'' +
                ", uri='" + uri + '\'' +
                ", version='" + version + '\'' +
                ", committed=" + committed +
                ", storageUrl='" + storageUrl + '\'' +
                '}';
    }
}
