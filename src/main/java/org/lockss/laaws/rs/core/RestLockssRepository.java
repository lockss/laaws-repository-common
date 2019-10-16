/*
 * Copyright (c) 2017-2019, Board of Trustees of Leland Stanford Jr. University,
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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpException;
import org.lockss.laaws.rs.model.ArtifactIdentifier;
import org.lockss.laaws.rs.util.ArtifactConstants;
import org.lockss.laaws.rs.util.ArtifactDataFactory;
import org.lockss.laaws.rs.util.ArtifactDataUtil;
import org.lockss.laaws.rs.model.Artifact;
import org.lockss.laaws.rs.util.NamedInputStreamResource;
import org.lockss.laaws.rs.model.ArtifactData;
import org.lockss.log.L4JLogger;
import org.lockss.util.jms.*;
import org.lockss.util.rest.*;
import org.lockss.util.rest.exception.*;
import org.lockss.util.time.TimeUtil;
import org.lockss.util.time.TimerUtil;
import org.lockss.util.time.Deadline;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.util.*;
import javax.jms.*;

/**
 * REST client implementation of the LOCKSS Repository API; makes REST
 * calls to a remote LOCKSS Repository REST server.
 *
 * Recently returned Artifacts are cached in an ArtifactCache so that
 * subsequent lookups can be satisfied without a REST roundtrip.
 */
public class RestLockssRepository implements LockssRepository {
  private final static L4JLogger log = L4JLogger.getLogger();

  public static final int DEFAULT_MAX_CACHE_SIZE = 500;

  private RestTemplate restTemplate;
  private URL repositoryUrl;

  /**
   * Constructor that takes a base URL to a remote LOCKSS Repository service, and uses an unmodified Spring REST
   * template client.
   *
   * @param repositoryUrl
   *          A {@code URL} containing the base URL of the remote LOCKSS Repository service.
   */
  public RestLockssRepository(URL repositoryUrl) {
    this(repositoryUrl, new RestTemplate());
  }

  /**
   * Constructor that takes a base URL to a remote LOCKSS Repository service, and an instance of Spring's
   * {@code RestTemplate}. Used for mainly for testing.
   *
   * @param repositoryUrl
   *          A {@code URL} containing the base URL of the remote LOCKSS Repository service.
   * @param restTemplate
   *          Instance of {@code RestTemplate} to use internally for remote REST calls.
   */
  public RestLockssRepository(URL repositoryUrl, RestTemplate restTemplate) {
    this.restTemplate = restTemplate;
    this.repositoryUrl = repositoryUrl;

    restTemplate.setErrorHandler(new DefaultResponseErrorHandler(){
	protected boolean hasError(HttpStatus statusCode) {
	  return false;
	}
      });

    // Set the buffer to false for streaming - still needed?
    //SimpleClientHttpRequestFactory factory = (SimpleClientHttpRequestFactory) this.restTemplate.getRequestFactory();
    //factory.setBufferRequestBody(false);
  }

  /**
   * Constructs a REST endpoint to an artifact in the repository.
   *
   * @param collection A {@code String} containing the collection ID.
   * @param artifactId A {@code String} containing the artifact ID.
   * @return A {@code URI} containing the REST endpoint to an artifact in the repository.
   */
  private URI artifactEndpoint(String collection, String artifactId) {
    String endpoint = String.format("%s/collections/%s/artifacts/%s", repositoryUrl, collection, artifactId);
    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(endpoint);
    return builder.build().encode().toUri();
  }

  /** Throws LockssNoSuchArtifactIdException if the response status is 404,
   * otherwise returns
   * @param e the LockssRestHttpException that was caught
   * @param artifactId A {@code String} containing the artifact ID.
   * @param msg used in error log and thrown exception
   */
  private void checkArtIdError(LockssRestHttpException e, String artifactId,
			       String msg)
      throws LockssNoSuchArtifactIdException {
    if (e.getHttpStatus().equals(HttpStatus.NOT_FOUND)) {
      log.warn(msg, e);
      throw new LockssNoSuchArtifactIdException(msg + ": " + artifactId, e);
    }
  }

  /**
   * Adds an instance of {@code ArtifactData} to the remote REST LOCKSS Repository server.
   *
   * Encodes an {@code ArtifactData} and its constituent parts into a multipart/form-data HTTP POST request for
   * transmission to a remote LOCKSS repository.
   *
   * @param artifactData
   *          An {@code ArtifactData} to add to the remote LOCKSS repository.
   * @return A {@code String} containing the artifact ID of the newly added artifact.
   */
  @Override
  public Artifact addArtifact(ArtifactData artifactData) throws IOException {
    if (artifactData == null)
      throw new IllegalArgumentException("ArtifactData is null");

    // Get artifact identifier
    ArtifactIdentifier artifactId = artifactData.getIdentifier();

    log.debug(
	      "Adding artifact to remote repository [collectionId: {}, auId: {}, uri: {}]",
	      artifactId.getCollection(),
	      artifactId.getAuid(),
	      artifactId.getUri()
	      );

    // Create a multivalue map to contain the multipart parts
    MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    parts.add("auid", artifactId.getAuid());
    parts.add("uri", artifactId.getUri());

    // Prepare artifact multipart headers
    HttpHeaders contentPartHeaders = new HttpHeaders();

    // This must be set or else AbstractResource#contentLength will read the entire InputStream to determine the
    // content length, which will exhaust the InputStream.
    contentPartHeaders.setContentLength(0); // TODO: Should be set to the length of the multipart body.
    contentPartHeaders.setContentType(MediaType.valueOf("application/http; msgtype=response"));

    // Prepare artifact multipart body
    try {
      Resource artifactPartResource =
	new NamedInputStreamResource("content",
				     ArtifactDataUtil.getHttpResponseStreamFromArtifactData(artifactData));

      // Add artifact multipart to multiparts list. The name of the part
      // must be "file" because that is what the Swagger-generated code
      // specifies.
      parts.add("file", new HttpEntity<>(artifactPartResource, contentPartHeaders));
    } catch (HttpException e) {
      String errMsg = String.format("Error generating HTTP response stream from artifact data: %s", e);
      log.error(errMsg);
      throw new IOException(errMsg);
    }

    // TODO: Create an attach optional artifact aspects
//        parts.add("aspectsParts", new NamedByteArrayResource("aspect1", "metadata bytes1".getBytes()));
//        parts.add("aspectsParts", new NamedByteArrayResource("aspect2", "metadata bytes2".getBytes()));
//        parts.add("aspectsParts", new NamedByteArrayResource("aspect3", "metadata bytes3".getBytes()));

    // POST body entity
    HttpEntity<MultiValueMap<String, Object>> multipartEntity = new HttpEntity<>(parts, null);

    // Construct REST endpoint to collection
    String endpoint = String.format("%s/collections/%s/artifacts", repositoryUrl, artifactId.getCollection());
    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(endpoint);

    // POST the multipart entity to the remote LOCKSS repository and return the result
    try {
      ResponseEntity<String> response =
	RestUtil.callRestService(restTemplate,
				 builder.build().encode().toUri(),
				 HttpMethod.POST,
				 multipartEntity,
				 String.class, "addArtifact");
      checkStatusOk(response);

      ObjectMapper mapper = new ObjectMapper();
      mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
		       false);
      Artifact res = mapper.readValue(response.getBody(), Artifact.class);
      artCache.put(res);
      return res;

    } catch (LockssRestException e) {
      log.error("Could not add artifact", e);
      throw e;
    }
  }

  /**
   * Returns the artifact with the specified artifactId
   *
   * @param artifactId
   * @return The {@code Artifact} with the artifactId, or null if none
   * @throws IOException
   */
  public Artifact getArtifactFromId(String artifactId) throws IOException {
    throw new UnsupportedOperationException();
  }

  /**
   * Retrieves an artifact from a remote REST LOCKSS Repository server.
   *
   * @param collection
   *          A {@code String} containing the collection ID.
   * @param artifactId
   *          A {@code String} containing the artifact ID of the artifact to retrieve from the remote repository.
   * @return The {@code ArtifactData} referenced by the artifact ID.
   * @throws IOException
   */
  @Override
  public ArtifactData getArtifactData(String collection, String artifactId) throws IOException {
    if ((collection == null) || (artifactId == null))
      throw new IllegalArgumentException("Null collection id or artifact id");

    try {
      ResponseEntity<Resource> response =
	RestUtil.callRestService(restTemplate,
				 artifactEndpoint(collection, artifactId),
				 HttpMethod.GET,
				 null,
				 Resource.class,
				 "getArtifactData");
      checkStatusOk(response);

      // TODO: Is response.getBody.getInputStream() backed by memory?
      // Or over a threshold, is it backed by disk?
      return ArtifactDataFactory.fromTransportResponseEntity(response);

    } catch (LockssRestHttpException e) {
      log.error("Could not get artifact data: {}", e.toString());
      checkArtIdError(e, artifactId, "Artifact Id not found");
      throw e;
    } catch (LockssRestException e) {
      log.error("Could not get artifact data", e);
      throw e;
    }
  }

  /**
   * Commits an artifact to this LOCKSS repository for permanent storage and inclusion in LOCKSS repository queries.
   *
   * @param collection
   *          A {code String} containing the collection ID containing the artifact to commit.
   * @param artifactId
   *          A {@code String} with the artifact ID of the artifact to commit to the repository.
   * @return An {@code Artifact} containing the updated artifact state information.
   * @throws IOException
   */
  @Override
  public Artifact commitArtifact(String collection, String artifactId) throws IOException {
    if ((collection == null) || (artifactId == null))
      throw new IllegalArgumentException("Null collection or artifactId");

    UriComponentsBuilder builder = UriComponentsBuilder.fromUri(artifactEndpoint(collection, artifactId))
      .queryParam("committed", "true");

    // Required by REST API specification
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.valueOf("multipart/form-data"));

    try {
      ResponseEntity<String> response =
	RestUtil.callRestService(restTemplate,
				 builder.build().encode().toUri(),
				 HttpMethod.PUT,
				 new HttpEntity<>(null, headers),
				 String.class,
				 "commitArtifact");
      checkStatusOk(response);

      ObjectMapper mapper = new ObjectMapper();
      mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
		       false);
      Artifact res = mapper.readValue(response.getBody(), Artifact.class);
      // Possible to commit out-of-order so we don't know whether this is
      // the latest
      artCache.put(res);
      return res;
    } catch (LockssRestHttpException e) {
      checkArtIdError(e, artifactId, "Could not commit; non-existent artifact id");
      log.error("Could not commit artifact id: {}", artifactId, e);
      throw e;
    } catch (LockssRestException e) {
      log.error("Could not commit artifact id: {}", artifactId, e);
      throw e;
    }
  }

  /**
   * Permanently removes an artifact from this LOCKSS repository.
   *
   * @param collection
   *          A {code String} containing the collection ID of the collection containing the artifact to delete.
   * @param artifactId
   *          A {@code String} with the artifact ID of the artifact to remove from this LOCKSS repository.
   * @throws IOException
   */
  @Override
  public void deleteArtifact(String collection, String artifactId) throws IOException {
    if ((collection == null) || (artifactId == null))
      throw new IllegalArgumentException("Null collection id or artifact id");

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    try {
      ResponseEntity<Void> response =
	RestUtil.callRestService(restTemplate,
				 artifactEndpoint(collection, artifactId),
				 HttpMethod.DELETE,
				 new HttpEntity<>(null, headers),
				 Void.class, "deleteArtifact");

      checkStatusOk(response);
      HttpStatus status = response.getStatusCode();

      if (status.is2xxSuccessful()) {
	return;
      }

    } catch (LockssRestHttpException e) {
      checkArtIdError(e, artifactId, "Could not remove artifact id");
      log.error("Could not remove artifact id: {}", artifactId, e);
      throw e;
    } catch (LockssRestException e) {
      log.error("Could not remove artifact id: {}", artifactId, e);
      throw e;
    }
  }

  /**
   *
   * @param collection
   * @param artifactId
   * @param parts
   * @return
   */
  private Artifact updateArtifactProperties(String collection, String artifactId, MultiValueMap<String, Object> parts) throws IOException {
    // Create PUT request entity
    HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(parts, null);

    // Submit PUT request and return artifact index data
    try {
      ResponseEntity<String> response =
	RestUtil.callRestService(restTemplate,
				 artifactEndpoint(collection, artifactId),
				 HttpMethod.PUT,
				 requestEntity,
				 String.class,
				 "updateArtifactProperties");
      checkStatusOk(response);

      ObjectMapper mapper = new ObjectMapper();
      mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
		       false);
      return mapper.readValue(response.getBody(), Artifact.class);

    } catch (LockssRestHttpException e) {
      log.error("Could not update artifact id: {}", artifactId, e);
      checkArtIdError(e, artifactId, "Could not update artifact id");
      throw e;
    } catch (LockssRestException e) {
      log.error("Could not update artifact id: {}", artifactId, e);
      throw e;
    }
  }

  /**
   * Returns a boolean indicating whether an artifact by an artifact ID exists in this LOCKSS repository.
   *
   * @param artifactId
   *          A String with the ArtifactData ID of the artifact to check for existence.
   * @return A boolean indicating whether an artifact exists in this repository.
   */
  @Override
  public Boolean artifactExists(String collection, String artifactId)
      throws IOException {
    if ((collection == null) || (artifactId == null))
      throw new IllegalArgumentException("Null collection id or artifact id");
    if (StringUtils.isEmpty(artifactId)) {
      throw new IllegalArgumentException("Null or empty identifier");
    }

    try {
      ResponseEntity<Void> response =
	RestUtil.callRestService(restTemplate,
				 artifactEndpoint(collection, artifactId),
				 HttpMethod.HEAD,
				 null,
				 Void.class,
				 "artifactExists");
      checkStatusOk(response);
      return true;
    } catch (LockssRestHttpException e) {
      if (e.getHttpStatus().equals(HttpStatus.NOT_FOUND)) {
	return false;
      }
      log.error("Could not get artifact data HEAD: {}", artifactId, e);
      throw e;
    } catch (LockssRestException e) {
      log.error("Could not get artifact data HEAD: {}", artifactId, e);
      throw e;
    }
  }

  /**
   * Returns a boolean indicating whether an artifact is committed in this LOCKSS repository.
   *
   * @param artifactId
   *          ArtifactData ID of the artifact to check committed status.
   * @return A boolean indicating whether the artifact is committed.
   */
  @Override
  public Boolean isArtifactCommitted(String collection, String artifactId)
      throws IOException {
    if ((collection == null) || (artifactId == null))
      throw new IllegalArgumentException("Null collection id or artifact id");
    if (StringUtils.isEmpty(artifactId)) {
      throw new IllegalArgumentException("Null or empty identifier");
    }

    try {
      ResponseEntity<Void> response =
	RestUtil.callRestService(restTemplate,
				 artifactEndpoint(collection, artifactId),
				 HttpMethod.HEAD,
				 null,
				 Void.class,
				 "isArtifactCommitted");
      checkStatusOk(response);

      HttpHeaders headers = response.getHeaders();
      String committedValue =
	headers.getFirst(ArtifactConstants.ARTIFACT_STATE_COMMITTED);

      if (committedValue == null) {
	String msg = String.format("Remote repository did not return %s header for artifact (Collection: %s, Artifact: %s)",
				   ArtifactConstants.ARTIFACT_STATE_COMMITTED,
				   collection,
				   artifactId);
	log.error(msg);
	throw new LockssRestInvalidResponseException(msg);
      }

      return "true".equalsIgnoreCase(committedValue);

    } catch (LockssRestHttpException e) {
      checkArtIdError(e, artifactId, "Non-existent artifact id");
      log.error("Could not determine artifact commit status", e);
      throw e;
    } catch (LockssRestException e) {
      log.error("Could not determine artifact commit status", e);
      throw e;
    }
  }

  /**
   * Provides the collection identifiers of the committed artifacts in the index.
   *
   * @return An {@code Iterator<String>} with the index committed artifacts
   * collection identifiers.
   */
  @Override
  public Iterable<String> getCollectionIds() throws IOException {
    String endpoint = String.format("%s/collections", repositoryUrl);

    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(endpoint);

    try {
      ResponseEntity<String> response =
	RestUtil.callRestService(restTemplate,
				 builder.build().encode().toUri(),
				 HttpMethod.GET,
				 null,
				 String.class,
				 "getCollectionIds");
      checkStatusOk(response);

      ObjectMapper mapper = new ObjectMapper();
      List<String> result = mapper.readValue((String)response.getBody(),
					     new TypeReference<List<String>>(){});
      return IteratorUtils.asIterable(result.iterator());
    } catch (LockssRestException e) {
      log.error("Could not get collection IDs", e);
      throw e;
    }
  }

  /**
   * Returns a list of Archival Unit IDs (AUIDs) in this LOCKSS repository collection.
   *
   * @param collection
   *          A {@code String} containing the LOCKSS repository collection ID.
   * @return A {@code Iterator<String>} iterating over the AUIDs in this LOCKSS repository collection.
   * @throws IOException
   */
  @Override
  public Iterable<String> getAuIds(String collection) throws IOException {
    if ((collection == null))
      throw new IllegalArgumentException("Null collection id");
    String endpoint = String.format("%s/collections/%s/aus", repositoryUrl, collection);

    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(endpoint);

    try {
      ResponseEntity<String> response =
	RestUtil.callRestService(restTemplate,
				 builder.build().encode().toUri(),
				 HttpMethod.GET,
				 null,
				 String.class,
				 "getAuIds");

      checkStatusOk(response);

      ObjectMapper mapper = new ObjectMapper();
      List<String> result =
	mapper.readValue((String)response.getBody(),
			 new TypeReference<List<String>>(){});
      return IteratorUtils.asIterable(result.iterator());

    } catch (LockssRestHttpException e) {
      if (e.getHttpStatus().equals(HttpStatus.NOT_FOUND)) {
	return IteratorUtils.asIterable(Collections.emptyIterator());
      }
      log.error("Could not get AUIDs", e);
      throw e;
    } catch (LockssRestException e) {
      log.error("Could not get AUIDs", e);
      throw e;
    }
  }

  /**
   * Returns an iterable object over artifacts, given a REST endpoint that returns artifacts.
   *
   * @param builder A {@code UriComponentsBuilder} containing a REST endpoint that returns artifacts.
   * @return An {@code Iterator<Artifact>} containing artifacts.
   */
  private Iterator<Artifact> getArtifacts(UriComponentsBuilder builder) throws IOException {
    return getArtifacts(builder.build().encode().toUri());
  }

  /**
   * Returns an iterable object over artifacts, given a REST endpoint that returns artifacts.
   *
   * @param endpoint A {@code URI} containing a REST endpoint that returns artifacts.
   * @return An {@code Iterator<Artifact>} containing artifacts.
   */
  private Iterator<Artifact> getArtifacts(URI endpoint) throws IOException {
    try {
      ResponseEntity<String> response =
	RestUtil.callRestService(restTemplate,
				 endpoint,
				 HttpMethod.GET,
				 null,
				 String.class,
				 "getArtifacts");
      checkStatusOk(response);

      ObjectMapper mapper = new ObjectMapper();
      mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
		       false);
      List<Artifact> result = mapper.readValue((String)response.getBody(),
					       new TypeReference<List<Artifact>>(){});
      return result.iterator();

    } catch (LockssRestHttpException e) {
      if (e.getHttpStatus().equals(HttpStatus.NOT_FOUND)) {
	return Collections.emptyIterator();
      }
      log.error("Could not fetch artifacts", e);
      throw e;
    } catch (LockssRestException e) {
      log.error("Could not fetch artifacts", e);
      throw e;
    }

  }

  /**
   * Returns the committed artifacts of the latest version of all URLs, from a specified Archival Unit and collection.
   *
   * @param collection
   *          A {@code String} containing the collection ID.
   * @param auid
   *          A {@code String} containing the Archival Unit ID.
   * @return An {@code Iterator<Artifact>} containing the latest version of all URLs in an AU.
   * @throws IOException
   */
  @Override
  public Iterable<Artifact> getArtifacts(String collection, String auid) throws IOException {
    if ((collection == null) || (auid == null))
      throw new IllegalArgumentException("Null collection id or au id");
    String endpoint = String.format("%s/collections/%s/aus/%s/artifacts", repositoryUrl, collection, auid);

    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(endpoint)
      .queryParam("version", "latest");

    return IteratorUtils.asIterable(artCache.cachingLatestIterator(getArtifacts(builder)));
  }

  /**
   * Returns the committed artifacts of all versions of all URLs, from a specified Archival Unit and collection.
   *
   * @param collection
   *          A String with the collection identifier.
   * @param auid
   *          A String with the Archival Unit identifier.
   * @return An {@code Iterator<Artifact>} containing the committed artifacts of all version of all URLs in an AU.
   */
  @Override
  public Iterable<Artifact> getArtifactsAllVersions(String collection, String auid) throws IOException {
    if ((collection == null) || (auid == null))
      throw new IllegalArgumentException("Null collection id or au id");
    String endpoint = String.format("%s/collections/%s/aus/%s/artifacts", repositoryUrl, collection, auid);

    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(endpoint)
      .queryParam("version", "all");

    return IteratorUtils.asIterable(getArtifacts(builder));
  }

  /**
   * Returns the committed artifacts of the latest version of all URLs matching a prefix, from a specified Archival
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
  @Override
  public Iterable<Artifact> getArtifactsWithPrefix(String collection, String auid, String prefix) throws IOException {
    if ((collection == null) || (auid == null) || (prefix == null))
      throw new IllegalArgumentException("Null collection id, au id or prefix");
    String endpoint = String.format("%s/collections/%s/aus/%s/artifacts", repositoryUrl, collection, auid);

    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(endpoint)
      .queryParam("urlPrefix", prefix);

    return IteratorUtils.asIterable(artCache.cachingLatestIterator(getArtifacts(builder)));
  }

  /**
   * Returns the committed artifacts of all versions of all URLs matching a prefix, from a specified Archival Unit and
   * collection.
   *
   * @param collection
   *          A String with the collection identifier.
   * @param auid
   *          A String with the Archival Unit identifier.
   * @param prefix
   *          A String with the URL prefix.
   * @return An {@code Iterator<Artifact>} containing the committed artifacts of all versions of all URLs matching a
   *         prefix from an AU.
   */
  @Override
  public Iterable<Artifact> getArtifactsWithPrefixAllVersions(String collection, String auid, String prefix) throws IOException {
    if ((collection == null) || (auid == null) || (prefix == null))
      throw new IllegalArgumentException("Null collection id, au id or prefix");
    String endpoint = String.format("%s/collections/%s/aus/%s/artifacts", repositoryUrl, collection, auid);

    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(endpoint)
      .queryParam("version", "all")
      .queryParam("urlPrefix", prefix);

    return IteratorUtils.asIterable(getArtifacts(builder));
  }

  /**
   * Returns the committed artifacts of all versions of all URLs matching a prefix, from a collection.
   *
   * @param collection
   *          A String with the collection identifier.
   * @param prefix
   *          A String with the URL prefix.
   * @return An {@code Iterator<Artifact>} containing the committed artifacts of all versions of all URLs matching a
   *         prefix.
   */
  @Override
  public Iterable<Artifact> getArtifactsWithPrefixAllVersionsAllAus(String collection, String prefix) throws IOException {
    if (collection == null || prefix == null)
      throw new IllegalArgumentException("Null collection id or prefix");
    String endpoint = String.format("%s/collections/%s/artifacts", repositoryUrl, collection);

    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(endpoint)
      .queryParam("version", "all")
      .queryParam("urlPrefix", prefix);

    return IteratorUtils.asIterable(getArtifacts(builder));
  }

  /**
   * Returns the committed artifacts of all versions of a given URL, from a specified Archival Unit and collection.
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
  @Override
  public Iterable<Artifact> getArtifactsAllVersions(String collection, String auid, String url) throws IOException {
    if ((collection == null) || (auid == null) || (url == null))
      throw new IllegalArgumentException("Null collection id, au id or url");
    String endpoint = String.format("%s/collections/%s/aus/%s/artifacts", repositoryUrl, collection, auid);

    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(endpoint)
      .queryParam("url", url)
      .queryParam("version", "all");

    return IteratorUtils.asIterable(getArtifacts(builder));
  }

  /**
   * Returns the committed artifacts of all versions of a given URL, from a specified collection.
   *
   * @param collection
   *          A {@code String} with the collection identifier.
   * @param url
   *          A {@code String} with the URL to be matched.
   * @return An {@code Iterator<Artifact>} containing the committed artifacts of all versions of a given URL.
   */
  @Override
  public Iterable<Artifact> getArtifactsAllVersionsAllAus(String collection, String url) throws IOException {
    if (collection == null || url == null)
      throw new IllegalArgumentException("Null collection id or url");
    String endpoint = String.format("%s/collections/%s/artifacts", repositoryUrl, collection);

    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(endpoint)
      .queryParam("url", url)
      .queryParam("version", "all");

    return IteratorUtils.asIterable(getArtifacts(builder));
  }

  /**
   * Returns the artifact of the latest version of given URL, from a specified Archival Unit and collection.
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
  @Override
  public Artifact getArtifact(String collection, String auid, String url) throws IOException {
    if ((collection == null) || (auid == null) || (url == null))
      throw new IllegalArgumentException("Null collection id, au id or url");
    Artifact cached = artCache.getLatest(collection, auid, url);
    if (cached != null) {
      return cached;
    }

    String endpoint = String.format("%s/collections/%s/aus/%s/artifacts", repositoryUrl, collection, auid);

    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(endpoint)
      .queryParam("url", url)
      .queryParam("version", "latest");

    try {
      ResponseEntity<String> response =
	RestUtil.callRestService(restTemplate,
				 builder.build().encode().toUri(),
				 HttpMethod.GET,
				 null,
				 String.class,
				 "getArtifact");

      checkStatusOk(response);

      ObjectMapper mapper = new ObjectMapper();
      mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
		       false);
      List<Artifact> artifacts =
	mapper.readValue((String) response.getBody(),
			 new TypeReference<List<Artifact>>(){});

      if (!artifacts.isEmpty()) {
	if (artifacts.size() > 1) {
	  log.warn(String.format(
				 "Expected one or no artifacts for latest version but got %d (Collection: %s, AU: %s, URL: %s)",
				 artifacts.size(),
				 collection,
				 url,
				 auid
				 ));
	}

	Artifact res = artifacts.get(0);
	if (res != null) {
	  // This is the latest, cache as that as well as real version
	  artCache.putLatest(res);
	}
	return res;
      }
      // No artifact found
      return null;

    } catch (LockssRestHttpException e) {
      if (! e.getHttpStatus().equals(HttpStatus.NOT_FOUND)) {
	log.error("Could not fetch artifact", e);
      }
      return null;
    } catch (LockssRestException e) {
      log.error("Could not fetch artifact", e);
      throw e;
    }
  }

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
   *          An Integer with the version.
   * @param includeUncommitted
   *          A boolean with the indication of whether an uncommitted artifact
   *          may be returned.
   * @return The {@code Artifact} of a given version of a URL, from a specified AU and collection.
   */
  @Override
  public Artifact getArtifactVersion(String collection, String auid, String url, Integer version, boolean includeUncommitted) throws IOException {
    if ((collection == null) || (auid == null) ||
	(url == null) || version == null)
      throw new IllegalArgumentException("Null collection id, au id, url or version");

    Artifact cached = artCache.get(collection, auid, url, version);
    if (cached != null) {
      return cached;
    }

    String endpoint = String.format("%s/collections/%s/aus/%s/artifacts", repositoryUrl, collection, auid);

    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(endpoint)
      .queryParam("url", url)
      .queryParam("version", version);

    if (includeUncommitted) {
      builder.queryParam("includeUncommitted", includeUncommitted);
    }

    try {
      ResponseEntity<String> response =
	RestUtil.callRestService(restTemplate,
				 builder.build().encode().toUri(),
				 HttpMethod.GET,
				 null,
				 String.class,
				 "getArtifactVersion");
      checkStatusOk(response);

      ObjectMapper mapper = new ObjectMapper();
      mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
		       false);
      List<Artifact> artifacts =
	mapper.readValue((String)response.getBody(),
			 new TypeReference<List<Artifact>>(){});

      if (!artifacts.isEmpty()) {
	// Warn if the server returned more than one artifact
	if (artifacts.size() > 1) {
	  log.warn(String.format("Expected one or no artifacts but got %d (Collection: %s, AU: %s, URL: %s, Version: %s)",
				 artifacts.size(),
				 collection,
				 auid,
				 url,
				 version
				 ));
	}

	Artifact res = artifacts.get(0);
	artCache.put(res);
	return res;
      }

      // No artifact found
      return null;

    } catch (LockssRestException e) {
      log.error("Could not fetch versioned artifact", e);
      return null;
    }
  }

  /**
   * Returns the size, in bytes, of AU in a collection.
   *
   * @param collection A {@code String} containing the collection ID.
   * @param auid       A {@code String} containing the Archival Unit ID.
   * @return A {@code Long} with the total size of the specified AU in bytes.
   */
  @Override
  public Long auSize(String collection, String auid) {
    if ((collection == null) || (auid == null))
      throw new IllegalArgumentException("Null collection id or au id");
    String endpoint = String.format("%s/collections/%s/aus/%s/size", repositoryUrl, collection, auid);

    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(endpoint)
      .queryParam("version", "all");

    try {
      ResponseEntity<String> response =
	RestUtil.callRestService(restTemplate,
				 builder.build().encode().toUri(),
				 HttpMethod.GET,
				 null,
				 String.class,
				 "auSize");

      checkStatusOk(response);

      ObjectMapper objectMapper = new ObjectMapper();
      return objectMapper.readValue(response.getBody(), Long.class);
    } catch (IOException e) {
      log.error("Could not determine AU size", e);
      return new Long(0);
    }

  }

  // RestUtil.callRestService() throws on non-2xx response codes; this is a
  // sanity check to ensure that. */
  private void checkStatusOk(ResponseEntity resp) {
    checkStatusOk(resp.getStatusCode());
  }

  private void checkStatusOk(HttpStatus status) {
    if (!status.is2xxSuccessful()) {
      throw new RuntimeException("Shouldn't happen: RestUtil returned non-200 result");
    }
  }

  /**
   * Checks if the remote repository is alive.
   *
   *
   * @return
   */
  private boolean checkAlive() {
    // TODO: Check Status API?
    return true;
  }

  /**
   * Returns a boolean indicating whether this repository is ready.
   *
   * @return
   */
  @Override
  public boolean isReady() {
    return checkAlive();
  }


  // ArtifactCache support.

  // Definitions for cache invalidate messages from repo service
  public static final String REST_ARTIFACT_CACHE_ID = null;
  public static final String REST_ARTIFACT_CACHE_TOPIC = "ArtifactCacheTopic";
  public static final String REST_ARTIFACT_CACHE_MSG_ACTION = "CacheAction";
  public static final String REST_ARTIFACT_CACHE_MSG_ACTION_INVALIDATE =
    "Invalidate";
  public static final String REST_ARTIFACT_CACHE_MSG_ACTION_FLUSH = "Flush";
  public static final String REST_ARTIFACT_CACHE_MSG_ACTION_ECHO = "Echo";
  public static final String REST_ARTIFACT_CACHE_MSG_ACTION_ECHO_RESP =
    "EchoResp";
  public static final String REST_ARTIFACT_CACHE_MSG_OP = "InvalidateOp";
  public static final String REST_ARTIFACT_CACHE_MSG_KEY = "ArtifactKey";

  // Artifact cache.  Disable by default; our client will enable if
  // desired
  private ArtifactCache artCache =
    new ArtifactCache(DEFAULT_MAX_CACHE_SIZE).enable(false);
  private JmsConsumer jmsConsumer;
  private JmsProducer jmsProducer;
  boolean isEnablingCache = false;
  boolean invalidateCheckCompleted = false;
  Deadline invCheckDeadline;

  /**
   * Enable the ArtifactCache
   * @param enable true to enable
   * @param fact JmsFactory to using to create a JMS consumer for cache
   * invalidate messages
   * @return this
   */
  public RestLockssRepository enableArtifactCache(boolean enable,
						  JmsFactory fact) {
    if (enable) {
      synchronized (this) {
	if (!artCache.isEnabled() && !isEnablingCache) {
	  makeJmsConsumer(fact);
	}
      }
    } else {
      artCache.enable(false);
    }
    return this;
  }

  /** Return true if enableArtifactCache(true) has been called, whether or
   * not the cache has actually been enabled yet. */
  public boolean isArtifactCacheEnabled() {
    return isEnablingCache || artCache.isEnabled();
  }

  private void makeJmsConsumer(JmsFactory fact) {
    isEnablingCache = true;
    new Thread(new Runnable() {
	@Override
	public void run() {
	  try {
	    log.debug("Creating JMS consumer");
	    while (jmsConsumer == null || jmsProducer == null) {
	      if (jmsConsumer == null) {
		try {
		  log.trace("Attempting to create JMS consumer");
		  jmsConsumer =
		    fact.createTopicConsumer(REST_ARTIFACT_CACHE_ID,
					     REST_ARTIFACT_CACHE_TOPIC,
					     new ArtifactCacheListener());
		  log.debug("Created JMS consumer: {}",REST_ARTIFACT_CACHE_TOPIC);
		} catch (JMSException | NullPointerException exc) {
		  log.trace("Could not establish JMS connection; sleeping and retrying");
		}
	      }
	      if (jmsProducer == null) {
		try {
		  log.trace("Attempting to create JMS producer");
		  jmsProducer =
		    fact.createTopicProducer(REST_ARTIFACT_CACHE_ID,
					     REST_ARTIFACT_CACHE_TOPIC);
		  log.debug("Created JMS producer: {}",
			    REST_ARTIFACT_CACHE_TOPIC);
		} catch (JMSException | NullPointerException e) {
		  log.error("Could not create JMS producer for {}",
			    REST_ARTIFACT_CACHE_ID, e);
		}
	      }
	      if (jmsConsumer != null && jmsProducer != null) {
		break;
	      }
	      TimerUtil.guaranteedSleep(1 * TimeUtil.SECOND);
	    }
	    // producer and consumer have been created, probe service with
	    // ECHO request to determine whether it support sending JMS
	    // cache invalidate messages, enable cache iff it responds.
	    while (!invalidateCheckCompleted) {
	      sendPing();
	      invCheckDeadline = Deadline.in(5 * TimeUtil.SECOND);
	      try {
		invCheckDeadline.sleep();
	      } catch (InterruptedException e) {
		// ignore
	      }
	    }
	    artCache.enable(true);
	    log.info("Enabled Artifact cache");
	  } finally {
	    isEnablingCache = false;
	  }
	}}).start();
  }

  protected void sendPing() {
    if (jmsProducer != null) {
      Map<String,Object> map = new HashMap<>();
      map.put(RestLockssRepository.REST_ARTIFACT_CACHE_MSG_ACTION,
	      RestLockssRepository.REST_ARTIFACT_CACHE_MSG_ACTION_ECHO);
      map.put(RestLockssRepository.REST_ARTIFACT_CACHE_MSG_KEY,
	      repositoryUrl.toString());
      try {
	jmsProducer.sendMap(map);
      } catch (javax.jms.JMSException e) {
	log.error("Couldn't send ping", e);
      }
    }
  }


  /** @return the ArtifactCache */
  public ArtifactCache getArtifactCache() {
    return artCache;
  }

  //
  // This is here, rather than in ArtifactCache, to make ArtifactCache
  // independent of the particular notification mechanism.
  //
  private class ArtifactCacheListener implements MessageListener {
    @Override
    public void onMessage(Message message) {
      try {
	Map<String,String> msgMap =
	  (Map<String,String>)JmsUtil.convertMessage(message);
	String action = msgMap.get(REST_ARTIFACT_CACHE_MSG_ACTION);
	String key = msgMap.get(REST_ARTIFACT_CACHE_MSG_KEY);
	log.debug2("Received Artifact cache notification: {} key: {}",
		   action, key);
	if (action != null) {
	  switch (action) {
	  case REST_ARTIFACT_CACHE_MSG_ACTION_INVALIDATE:
	    artCache.invalidate(msgOp(msgMap.get(REST_ARTIFACT_CACHE_MSG_OP)),
				key);
	    break;
	  case REST_ARTIFACT_CACHE_MSG_ACTION_FLUSH:
	    log.debug("Flushing Artifact cache");
	    artCache.flush();
	    break;
	  case REST_ARTIFACT_CACHE_MSG_ACTION_ECHO_RESP:
	    if (repositoryUrl.toString().equals(key)) {
	      invalidateCheckCompleted = true;
	      if (invCheckDeadline != null) {
		invCheckDeadline.expire();
	      }
	      log.debug("invalidateCheckCompleted");
	    }
	    break;
	  case REST_ARTIFACT_CACHE_MSG_ACTION_ECHO:
	    // expected, ignore
	    break;
	  default:
	    log.warn("Unknown message action: {}", action);
	  }
	}
      } catch (JMSException | RuntimeException e) {
	log.error("Malformed Artifact cache message: {}", message, e);
      }
    }
  }

  ArtifactCache.InvalidateOp msgOp(String op) throws IllegalArgumentException {
    return Enum.valueOf(ArtifactCache.InvalidateOp.class, op);
  }
  
}
