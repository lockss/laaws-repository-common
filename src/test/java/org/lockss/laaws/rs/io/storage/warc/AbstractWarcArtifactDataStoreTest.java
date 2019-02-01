/*

Copyright (c) 2000-2018, Board of Trustees of Leland Stanford Jr. University,
All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
this list of conditions and the following disclaimer in the documentation and/or
other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors
may be used to endorse or promote products derived from this software without
specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

*/

package org.lockss.laaws.rs.io.storage.warc;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.concurrent.Future;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.http.ProtocolVersion;
import org.apache.http.StatusLine;
import org.apache.http.message.BasicStatusLine;
import org.archive.format.warc.WARCConstants;
import org.archive.io.ArchiveRecordHeader;
import org.archive.io.warc.WARCRecord;
import org.junit.jupiter.api.*;
import org.lockss.laaws.rs.core.BaseLockssRepository;
import org.lockss.laaws.rs.core.LockssRepository;
import org.lockss.laaws.rs.io.index.ArtifactIndex;
import org.lockss.laaws.rs.io.index.VolatileArtifactIndex;
import org.lockss.laaws.rs.model.*;
import org.lockss.laaws.rs.util.ArtifactConstants;
import org.lockss.log.L4JLogger;
import org.lockss.util.test.LockssTestCase5;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractWarcArtifactDataStoreTest<WADS extends WarcArtifactDataStore> extends LockssTestCase5 {
  private final static L4JLogger log = L4JLogger.getLogger();

  protected WADS store;

  protected abstract WADS makeWarcArtifactDataStore() throws IOException;
  protected abstract WADS makeWarcArtifactDataStore(WADS otherStore) throws IOException;

  @BeforeEach
  public void setupTestContext() throws IOException {
    store = makeWarcArtifactDataStore();
  }

  protected abstract void runTestGetTmpWarcBasePath();

  @Test
  public void testGetTmpWarcBasePath() {
    runTestGetTmpWarcBasePath();
  }

  @Test
  public void testInitCollection() throws Exception {
    store.initCollection("collection");
  }

  @Test
  public void testInitAu() throws Exception {
    store.initAu("collection", "auid");
  }

  @Test
  public void testInitWarc() throws Exception {
    String warcName = UUID.randomUUID().toString(); // FIXME: Use File.createTempFile(...)
    String warcPath = store.getTmpWarcBasePath() + "/" + warcName;

    store.initWarc(warcPath);
    assertTrue(isFile(getAbsolutePath(warcPath)));
  }

  /**
   * Tests for the temporary WARC reloading mechanism in {@code WarcArtifactDataStore}.
   *
   * @throws Exception
   */
  @Test
  public void testReloadTempWarcs() throws Exception {
    try {
      store.setArtifactIndex(null);
      store.reloadTmpWarcs();
      fail("Expected IllegalStateException");
    } catch (IllegalStateException e) {
      // OK
    }

    runTestReloadTempWarcs(true, true);
    runTestReloadTempWarcs(true, false);
    runTestReloadTempWarcs(false, true);
    runTestReloadTempWarcs(false, false);
  }

  /**
   * Runs tests against the temporary WARC reloading mechanism of {@code WarcArtifactDataStore} implementations. It does
   * this by asserting against the effect of adding one artifact then reloading the temporary WARC.
   *
   * @param commit A {@code boolean} indicating whether the artifact should be committed.
   * @param expire A {@code boolean} indicating whether the artifact should be expired.
   * @throws Exception
   */
  private void runTestReloadTempWarcs(boolean commit, boolean expire) throws Exception {
    // Instantiate a new WARC artifact data store
    store = makeWarcArtifactDataStore();
    assertNotNull(store);

    // Temporary WARCs directory storage paths
    String fullTmpWarcPath = new File(store.getBasePath(), store.getTmpWarcBasePath()).getPath();
    String tmpWarcsPathUrl = store.makeStorageUrl(store.getTmpWarcBasePath());

    // Configure WARC artifact data store with a newly instantiated volatile artifact index
    ArtifactIndex index = new VolatileArtifactIndex();
    store.setArtifactIndex(index);
    assertEquals(index, store.getArtifactIndex());

    // Initialize the data store
    index.initArtifactIndex();
    store.initArtifactDataStore();
    assertTrue(store.isReady());

    // Assert empty temporary WARCs directory
    Collection<String> tmpWarcs = store.findWarcs(fullTmpWarcPath);
    assertEquals(0, tmpWarcs.size());

    // Add an artifact to the store and index
    ArtifactData ad = generateTestArtifactData("coll1", "auid1", "uri1", 1, 1024);
    store.addArtifactData(ad);
    Artifact artifact = index.indexArtifact(ad);

    // Get the artifact ID
    String artifactId = artifact.getId();

    if (commit) {
      // Commit to artifact data store
      Future<Artifact> artifactFuture = store.commitArtifactData(artifact);

      // Wait for data store commit (copy from temporary to permanent storage) to complete
      artifact = artifactFuture.get(); // TODO: Enable a timeout
      assertNotNull(artifact);
      assertTrue(artifact.getCommitted());

      // Mark artifact as committed in the artifact index and update its storage URL
//      index.commitArtifact(artifactId);
//      index.updateStorageUrl(artifact.getId(), artifact.getStorageUrl());

      // Assert that the storage URL points to a WARC that is not in the temporary WARCs directory
      assertFalse(artifact.getStorageUrl().startsWith(tmpWarcsPathUrl));
      assertTrue(isFile(new URI(artifact.getStorageUrl()).getPath()));
    } else {
      // Assert that the storage URL points to a WARC within the temporary WARCs directory
      assertTrue(artifact.getStorageUrl().startsWith(tmpWarcsPathUrl));
    }

    // Retrieve the artifact from the index
    artifact = index.getArtifact(artifactId);
    assertNotNull(artifact);

    // Assert commit status of artifact
    assertEquals(commit, artifact.getCommitted());

    // Assert one temporary WARC file has been created
    tmpWarcs = store.findWarcs(fullTmpWarcPath);
    assertEquals(1, tmpWarcs.size());

    // Restart WARC data store
    log.info("Reloading WARC data store");
    store = makeWarcArtifactDataStore(store);
    store.setArtifactIndex(index);

    if (expire) {
      // Set the data store to expire artifacts immediately
      store.setUncommittedArtifactExpiration(0);
      assertEquals(0, store.getUncommittedArtifactExpiration());
    }

    // Initialize the data store again
    store.initArtifactDataStore();
    assertTrue(store.isReady());

    // Scan directories for temporary WARC files and assert its state
    tmpWarcs = store.findWarcs(fullTmpWarcPath);

    if (!expire && !commit) {
      // Artifact is neither expired nor committed yet: temporary WARC containing it should NOT have been removed
      assertEquals(1, tmpWarcs.size());
    } else {
      // Artifact is either expired or committed (or both): the temporary WARC containing it should have been removed
      log.info("commit = {}, expire = {}", commit, expire);
      assertEquals(0, tmpWarcs.size());
    }
  }

  /**
   * Generates an {@code ArtifactData} belonging to a random collection and AUID.
   *
   * @param collections
   * @param auids
   * @return
   * @throws IOException
   */
  private ArtifactData generateTestArtifactData(List<String> collections, List<String> auids) throws IOException {
    Random randomSrc = new Random();
    String collection = collections.get(randomSrc.nextInt(collections.size()));
    String auid = auids.get(randomSrc.nextInt(auids.size()));

    // Pick random parameters for this artifact
    int contentLength = (int) Math.floor(Math.random() * FileUtils.ONE_MB * 10);

    // Random artifact URI
    String artifactUri = UUID.randomUUID().toString();

    return generateTestArtifactData(collection, auid, artifactUri, 1, contentLength);
  }

  private ArtifactData generateTestArtifactData(String collection, String auid, String uri, int version, long length) throws IOException {
    // HTTP status (200 OK) for use volatile ArtifactData's we'll add to the repository
    StatusLine statusOK = new BasicStatusLine(new ProtocolVersion("HTTP", 1,1), 200, "OK");

    // Create an artifact and add it to the data store
    ArtifactIdentifier id = new ArtifactIdentifier(UUID.randomUUID().toString(), collection, auid, uri, version);

    // Generate this artifact's data
    ByteArrayOutputStream baos1 = new ByteArrayOutputStream((int) length);
    byte[] content = new byte[(int) length];
//    Arrays.fill(content, (byte) 0);
    new Random().nextBytes(content);
    baos1.write(content);
    baos1.close(); // to satisfy static analyzers

    ArtifactData ad = new ArtifactData(id, null, baos1.toInputStream(), statusOK);

    return ad;
  }

  @Test
  public void testAddArtifactData_null() throws Exception {
    try {
      Artifact artifact = store.addArtifactData(null);
      fail("Expected an IllegalArgumentException to be thrown");
    } catch (IllegalArgumentException e) {
      assertEquals("Null artifact data", e.getMessage());
    }

    try {
      ArtifactData ad = generateTestArtifactData("collection", "auid", "uri", 1, 1024);
      ad.setIdentifier(null);
      Artifact artifact = store.addArtifactData(ad);
      fail("Expected an IllegalArgumentException to be thrown");
    } catch (IllegalArgumentException e) {
      assertEquals("Artifact data has null identifier", e.getMessage());
    }

    return;
  }

  @Test
  public void testAddArtifactData_success() throws Exception {
    ArtifactData ad = generateTestArtifactData("collection", "auid", "uri", 1, 1024);
    assertNotNull(ad);

    ArtifactIdentifier ai = ad.getIdentifier();
    assertNotNull(ai);

    // Add the artifact data
    Artifact a = store.addArtifactData(ad);

    // Assert things about the artifact we got back
    assertNotNull(a);
    assertEquals(ai, a.getIdentifier());
    assertEquals(ai.getId(), a.getId());
    assertEquals(ai.getCollection(), a.getCollection());
    assertEquals(ai.getAuid(), a.getAuid());
    assertEquals(ai.getUri(), a.getUri());
    assertEquals(ai.getVersion(), a.getVersion());
    assertFalse(a.getCommitted());

    assertEquals(1024, a.getContentLength());
    assertEquals(ad.getContentDigest(), a.getContentDigest());

    // Assert temporary WARC directory exists
    assertTrue(isDirectory(getAbsolutePath(store.getTmpWarcBasePath())));

    // Assert things about the artifact's storage URL
    String storageUrl = a.getStorageUrl();
    assertNotNull(storageUrl);
    assertFalse(storageUrl.isEmpty());
    assertTrue(isValidStorageUrl(storageUrl));

    String artifactWarcPath = getPathFromStorageUrl(storageUrl);
    assertTrue(isFile(artifactWarcPath));

    assertNotNull(store.getTmpWarcBasePath());
    assertNotNull(getAbsolutePath(store.getTmpWarcBasePath()));

    log.info("artifactWarcPath = {}", artifactWarcPath);
    log.info("getTmpWarcBasePath = {}", getAbsolutePath(store.getTmpWarcBasePath()));
    assertTrue(artifactWarcPath.startsWith(getAbsolutePath(store.getTmpWarcBasePath())));
  }

  @Test
  public void testCommitArtifact() throws Exception {
    try {
      store.commitArtifactData(null);
      fail("Expected NullPointerException to be thrown");
    } catch (NullPointerException e) {
      assertEquals("Artifact is null", e.getMessage());
    }

    try {
      ArtifactData ad = generateTestArtifactData("collection", "auid", "uri", 1, 424L);
      Artifact artifact = store.addArtifactData(ad);
      store.commitArtifactData(artifact);
      fail("Expected IllegalStateException to be thrown");
    } catch (IllegalStateException e) {
      assertEquals("Artifact index is null", e.getMessage());
    }

    // Set an artifact index
    ArtifactIndex index = new VolatileArtifactIndex();
    store.setArtifactIndex(index);

    // Add an artifact to store
    ArtifactData ad = generateTestArtifactData("collection", "auid", "uri", 1, 424L);
    Artifact artifact_store = store.addArtifactData(ad);
    assertNotNull(artifact_store);
    assertFalse(artifact_store.getCommitted());

    // Add an artifact to index
    Artifact artifact_index = index.indexArtifact(ad);
    assertNotNull(artifact_index);
    assertFalse(artifact_index.getCommitted());

    // Assert artifact state in store and index are the same
    assertEquals(artifact_index, artifact_store);

    // Commit this artifact
    Future<Artifact> future = store.commitArtifactData(artifact_store);
    assertNotNull(future);
    Artifact committedArtifact = future.get();

    // Verify that the store has recorded it as committed
    assertNotNull(committedArtifact);
    assertTrue(committedArtifact.getCommitted());
    assertTrue(store.getArtifactData(committedArtifact).getRepositoryMetadata().isCommitted());
    assertTrue(index.getArtifact(committedArtifact.getId()).getCommitted());

    // TODO: Verify storage URL is not temporary
    log.info("storageURL = {}", committedArtifact.getStorageUrl());
  }

  @Test
  public void testDeleteArtifact() throws Exception {
    try {
      store.deleteArtifactData(null);
      fail("Expected NullPointerException to be thrown");
    } catch (NullPointerException npe) {
      assertEquals("Artifact is null", npe.getMessage());
    }

    try {
      // Add an artifact
      ArtifactData ad = generateTestArtifactData("collection", "auid", "uri", 1, 1024);
      Artifact artifact = store.addArtifactData(ad);
      assertNotNull(artifact);

      // Delete the artifact from the artifact store
      RepositoryArtifactMetadata metadata = store.deleteArtifactData(artifact);

      // Verify that the repository metadata reflects the artifact is deleted
      assertTrue(metadata.isDeleted());

      // And verify we get a null when trying to retrieve it after delete
//      TODO: assertNull(store.getArtifactData(artifact));
    } catch (IOException e) {
      fail("Unexpected IOException caught");
    }
  }

  @Test
  public void testGetArtifactData() throws Exception {
    // Attempt retrieving the artifact with a null argument
    try {
      store.getArtifactData(null);
      fail("Expected NullPointerException to be thrown");
    } catch (NullPointerException e) {
      assertEquals("Artifact is null", e.getMessage());
    }

    // Attempt retrieving an artifact that doesn't exist
    Artifact badArtifact = new Artifact(
        "badArtifactId",
        "coll3",
        "auid3",
        "uri",
        1,
        false,
        "fake",
        0,
        "ok"
    );

    try {
      ArtifactData badArtifactData = store.getArtifactData(badArtifact);
      fail("Expected IllegalArgumentException to be thrown");
    } catch (IllegalArgumentException e) {
      assertEquals("Bad storage URL", e.getMessage());
    }

    // Attempt a successful retrieval
    ArtifactData originalData = generateTestArtifactData("collection", "auid", "uri", 1, 1024);

    Artifact artifact = store.addArtifactData(originalData);
    assertNotNull(artifact);

    ArtifactData storedData = store.getArtifactData(artifact);
    assertNotNull(storedData);

//    assertEquals(originalData, storedData);
    assertEqualArtifactData(originalData, storedData);
  }

  private void assertEqualArtifactData(ArtifactData expected, ArtifactData actual) {
    assertEquals(expected.getIdentifier(), actual.getIdentifier());
//    assertEquals(expected.getHttpStatus(), actual.getHttpStatus());
    assertEquals(expected.getMetadata(), actual.getMetadata());
//    assertEquals(expected.getRepositoryMetadata(), actual.getRepositoryMetadata());
    assertEquals(expected.getStorageUrl(), actual.getStorageUrl());
    assertEquals(expected.getContentDigest(), actual.getContentDigest());
    assertEquals(expected.getContentLength(), actual.getContentLength());
//    assertSameBytes(expected.getInputStream(), actual.getInputStream());
  }

  @Test
  public void testWriteArtifactData() throws Exception {
    ArtifactData ad = generateTestArtifactData("collection", "auid", "uri", 1, 1024);
    assertNotNull(ad);

    ArtifactIdentifier ai = ad.getIdentifier();
    assertNotNull(ai);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    // Serialize artifact data to byte stream
    store.writeArtifactData(ad, baos);

    log.info("str = {}", baos.toString());

    // Transform WARC record byte stream to WARCRecord object
    WARCRecord record = new WARCRecord(new ByteArrayInputStream(baos.toByteArray()), getClass().getSimpleName(), 0);

    // Assert things about the WARC record
    assertNotNull(record);

    ArchiveRecordHeader headers = record.getHeader();
    assertNotNull(headers);

    // Assert mandatory WARC headers
    log.info("headers = {}", headers);
    log.info("headers.getUrl() = {}", headers.getUrl());

    log.info("headers.HEADER_KEY_ID = {}", headers.getHeaderValue(WARCConstants.HEADER_KEY_ID));
    log.info("headers.getRecordIdentifier() = {}", headers.getRecordIdentifier());
    log.info("headers.HEADER_KEY_ID = {}", headers.getReaderIdentifier());

//    assertEquals(WarcArtifactDataStore.createRecordHeader());

    assertEquals(WARCConstants.WARCRecordType.response,
        WARCConstants.WARCRecordType.valueOf((String)headers.getHeaderValue(WARCConstants.HEADER_KEY_TYPE)));

    // Assert LOCKSS headers
    assertEquals(ai.getId(), headers.getHeaderValue(ArtifactConstants.ARTIFACT_ID_KEY));
    assertEquals(ai.getCollection(), headers.getHeaderValue(ArtifactConstants.ARTIFACT_COLLECTION_KEY));
    assertEquals(ai.getAuid(), headers.getHeaderValue(ArtifactConstants.ARTIFACT_AUID_KEY));
    assertEquals(ai.getUri(), headers.getHeaderValue(ArtifactConstants.ARTIFACT_URI_KEY));
    assertEquals(ai.getVersion(), Integer.valueOf((String)headers.getHeaderValue(ArtifactConstants.ARTIFACT_VERSION_KEY)));
    assertEquals(ad.getContentLength(), Long.valueOf((String)headers.getHeaderValue(ArtifactConstants.ARTIFACT_LENGTH_KEY)).longValue());

    // TODO: Assert content
  }

  protected abstract boolean isValidStorageUrl(String storageUrl);

  private String getPathFromStorageUrl(String storageUrl) throws URISyntaxException {
    return new URI(storageUrl).getPath();
  }

  @Test
  public void testGetBasePath() throws Exception { // FIXME
//    assertEquals(tmpRepoBaseDir.getAbsolutePath(), store.getBasePath());
  }

  @Test
  public void testSetThresholdWarcSize() throws Exception {
    assertEquals(100L * FileUtils.ONE_MB, WarcArtifactDataStore.DEFAULT_THRESHOLD_WARC_SIZE);
    assertEquals(WarcArtifactDataStore.DEFAULT_THRESHOLD_WARC_SIZE, store.getThresholdWarcSize());
    assertThrows(IllegalArgumentException.class, () -> store.setThresholdWarcSize(-1234L));
    store.setThresholdWarcSize(0L);
    assertEquals(0L, store.getThresholdWarcSize());
    store.setThresholdWarcSize(10L * FileUtils.ONE_KB);
    assertEquals(10L * FileUtils.ONE_KB, store.getThresholdWarcSize());
  }
  
  @Test
  public void testGetCollectionPath() throws Exception {
    ArtifactIdentifier ident1 = new ArtifactIdentifier("coll1", null, null, 0);
    assertEquals("/collections/coll1", store.getCollectionPath(ident1));
    assertEquals(store.getCollectionPath(ident1.getCollection()),
                 store.getCollectionPath(ident1));
  }
  
  @Test
  public void testGetAuPath() throws Exception {
    ArtifactIdentifier ident1 = new ArtifactIdentifier("coll1", "auid1", null, 0);
    assertEquals("/collections/coll1/au-" + DigestUtils.md5Hex("auid1"),
                 store.getAuPath(ident1));
    assertEquals(store.getAuPath(ident1.getCollection(), ident1.getAuid()),
                 store.getAuPath(ident1));
  }
  
  @Test
  public void testGetSealedWarcPath() throws Exception {
    assertEquals("/sealed", store.getSealedWarcsPath());
  }
  
  @Test
  public void testGetSealedWarcName() throws Exception {
    String warcName = store.generateSealedWarcName("coll1", "auid1");
    assertThat(warcName, startsWith("coll1_au-" + DigestUtils.md5Hex("auid1") + "_"));
    assertThat(warcName, endsWith(".warc"));
    String timestamp = warcName.split("_")[2].split("artifacts.warc")[0];
    // DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS") does not parse in Java 8: https://bugs.openjdk.java.net/browse/JDK-8031085
    ZonedDateTime actual = ZonedDateTime.parse(timestamp, new DateTimeFormatterBuilder().appendPattern("yyyyMMddHHmmss").appendValue(ChronoField.MILLI_OF_SECOND, 3).toFormatter().withZone(ZoneId.of("UTC")));
    ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
    assertTrue(actual.isAfter(now.minusSeconds(5L)) && actual.isBefore(now.plusSeconds(5L)));
  }
  
  @Test
  public void testGetAuArtifactsWarcPath() throws Exception {
    // FIXME assertEquals(getBasePath() + expectedPath, getBasePath() + store.methodCall(...)) should be assertEquals(expectedPath, store.methodCall(...))
    ArtifactIdentifier ident1 = new ArtifactIdentifier("coll1", "auid1", null, 0);
    String expectedAuDirPath = store.getBasePath() + "/collections/coll1/au-" + DigestUtils.md5Hex("auid1");
    String expectedAuArtifactsWarcPath = expectedAuDirPath + "/" + store.getActiveWarcName("coll1", "auid1");
    assertFalse(pathExists(expectedAuDirPath)); // Not created until an artifact data is added
    assertEquals(expectedAuArtifactsWarcPath, store.getBasePath() + store.getActiveWarcPath(ident1));
    // FIXME assert that getActiveWarcPath() returns the same for ident1 and ident1.getCollection()+ident1.getAuid()
    // FIXME assert that the path exists now
  }
  
  @Test
  public void testGetAuMetadataWarcPath() throws Exception {
    ArtifactIdentifier ident1 = new ArtifactIdentifier("coll1", "auid1", null, 0);
    RepositoryArtifactMetadata md1 = new RepositoryArtifactMetadata(ident1);
    String expectedAuBaseDirPath = "/collections/coll1/au-" + DigestUtils.md5Hex("auid1");
    String expectedMetadataWarcPath = expectedAuBaseDirPath + "/lockss-repo.warc";
    assertFalse(pathExists(expectedAuBaseDirPath)); // Not created until an artifact data is added
    assertEquals(expectedMetadataWarcPath, store.getAuMetadataWarcPath(ident1, md1));
  }

  @Test
  public void testUpdateArtifactMetadata() {
    try {
      ArtifactData ad = generateTestArtifactData("collection", "auid", "uri", 1, 1024);
      ArtifactIdentifier aid1 = ad.getIdentifier();

      store.addArtifactData(ad);
      RepositoryArtifactMetadata md1updated = new RepositoryArtifactMetadata(aid1, true, false);

      RepositoryArtifactMetadata metadata = store.updateArtifactMetadata(aid1, md1updated);
      assertNotNull(metadata);

      assertEquals(md1updated.getArtifactId(), metadata.getArtifactId());
      assertTrue(metadata.isCommitted());

    } catch (IOException e) {
      fail("Unexpected IOException caught");
    }
  }

  protected abstract boolean pathExists(String path) throws IOException;
  protected abstract boolean isDirectory(String path) throws IOException;
  protected abstract boolean isFile(String path) throws IOException;
  protected abstract String getAbsolutePath(String path);

  protected abstract String testMakeStorageUrl_getExpected(ArtifactIdentifier ident, long offset, long length) throws Exception;

  @Test
  public void testMakeStorageUrl() throws Exception {
    ArtifactIdentifier ident1 = new ArtifactIdentifier("coll1", "auid1", "http://example.com/u1", 1);
    String artifactsWarcPath = store.getActiveWarcPath(ident1);
    String expected = testMakeStorageUrl_getExpected(ident1, 1234L, 5678L);
    String actual = store.makeStorageUrl(artifactsWarcPath, 1234L, 5678L);
    assertEquals(expected, actual);
  }

  @Test
  public void testRebuildIndex() throws Exception {
    // Instances of artifact index to populate and compare
    ArtifactIndex index1 = new VolatileArtifactIndex();
    ArtifactIndex index2 = new VolatileArtifactIndex();

    //// Create and populate first index by adding new artifacts to a repository
    store.setArtifactIndex(index1);
    LockssRepository repository = new BaseLockssRepository(index1, store);

    // Add first artifact to the repository - don't commit
    ArtifactData ad1 = generateTestArtifactData("collection1", "auid1", "uri1", 1, 1024);
    Artifact a1 = repository.addArtifact(ad1);

    // Add second artifact to the repository - commit
    ArtifactData ad2 = generateTestArtifactData("collection1", "auid1", "uri2", 1, 1024);
    Artifact a2 = repository.addArtifact(ad2);
    repository.commitArtifact(a2);

    // Add third artifact to the repository - don't commit but immediately delete
    ArtifactData ad3 = generateTestArtifactData("collection1", "auid1", "uri3", 1, 1024);
    Artifact a3 = repository.addArtifact(ad3);
    repository.deleteArtifact(a3);

    // Add fourth artifact to the repository - commit and delete
    ArtifactData ad4 = generateTestArtifactData("collection1", "auid1", "uri4", 1, 1024);
    Artifact a4 = repository.addArtifact(ad4);
    repository.commitArtifact(a4);
    repository.deleteArtifact(a4);

    // Populate second index by rebuilding
    store.rebuildIndex(index2);

    //// Compare indexes

    // Compare collections IDs
    List<String> cids1 = IteratorUtils.toList(index1.getCollectionIds().iterator());
    List<String> cids2 = IteratorUtils.toList(index2.getCollectionIds().iterator());
    if (!(cids1.containsAll(cids2) && cids2.containsAll(cids1))) {
      fail(String.format("Expected both the original and rebuilt artifact indexes to contain the same set of collection IDs: %s vs %s", cids1, cids2));
    }

    // Iterate over the collection IDs
    for (String cid : cids1) {
      // Compare the set of AUIDs
      List<String> auids1 = IteratorUtils.toList(index1.getAuIds(cid).iterator());
      List<String> auids2 = IteratorUtils.toList(index2.getAuIds(cid).iterator());
      if (!(auids1.containsAll(auids2) && auids2.containsAll(auids1))) {
        fail("Expected both the original and rebuilt artifact indexes to contain the same set of AUIDs");
      }

      // Iterate over AUIDs
      for (String auid : auids1) {
        List<Artifact> artifacts1 = IteratorUtils.toList(index1.getAllArtifacts(cid, auid, true).iterator());
        List<Artifact> artifacts2 = IteratorUtils.toList(index2.getAllArtifacts(cid, auid, true).iterator());

        // Debugging
        artifacts1.forEach(artifact -> log.info(String.format("Artifact from artifacts1: %s", artifact)));
        artifacts2.forEach(artifact -> log.info(String.format("Artifact from artifacts2: %s", artifact)));

        if (!(artifacts1.containsAll(artifacts2) && artifacts2.containsAll(artifacts1))) {
          fail("Expected both the original and rebuilt artifact indexes to contain the same set of artifacts");
        }
      }
    }
  }

  @Test
  public void testRebuildIndexSealed() throws Exception {
    // Instances of artifact index to populate and compare
    ArtifactIndex index3 = new VolatileArtifactIndex();
    ArtifactIndex index4 = new VolatileArtifactIndex();

    //// Create and populate first index by adding new artifacts to a repository
    store.setArtifactIndex(index3);
    LockssRepository repository = new BaseLockssRepository(index3, store);

    // The WARC records for the two artifacts here end up being 586 bytes each.
    store.setThresholdWarcSize(1024L);

    // HTTP status (200 OK) for use volatile ArtifactData's we'll add to the repository
    StatusLine status200 = new BasicStatusLine(new ProtocolVersion("HTTP", 1,1), 200, "OK");

    // Create an artifact and add it to the data store
    ArtifactIdentifier ident1 = new ArtifactIdentifier(UUID.randomUUID().toString(), "coll1", "auid1", "http://example.com/u1", 1);
    org.apache.commons.io.output.ByteArrayOutputStream baos1 = new org.apache.commons.io.output.ByteArrayOutputStream(150);
    for (int i = 0 ; i < 150 ; ++i) {
      baos1.write('a');
    }
    ArtifactData dat1 = new ArtifactData(ident1, null, baos1.toInputStream(), status200);
    Artifact art1 = store.addArtifactData(dat1);
    baos1.close(); // to satisfy static analyzers

    // Register the artifact in the index
    index3.indexArtifact(dat1);
    repository.commitArtifact(art1);

    // Add another artifact to the store - this will add another 586 bytes while should trigger a seal
    ArtifactIdentifier ident2 = new ArtifactIdentifier(UUID.randomUUID().toString(), "coll1", "auid1", "http://example.com/u2", 1);
    org.apache.commons.io.output.ByteArrayOutputStream baos2 = new ByteArrayOutputStream(150);
    for (int i = 0 ; i < 150 ; ++i) {
      baos2.write('b');
    }
    ArtifactData dat2 = new ArtifactData(ident2, null, baos2.toInputStream(), status200);
    Artifact art2 = store.addArtifactData(dat2);
    baos2.close(); // to satisfy static analyzers

    // Register the second artifact in the index
    index3.indexArtifact(dat2);
    repository.commitArtifact(art2);

    // Populate second index by rebuilding
    store.rebuildIndex(index4);

    //// Compare indexes

    // Compare collections IDs
    List<String> cids3 = IteratorUtils.toList(index3.getCollectionIds().iterator());
    List<String> cids4 = IteratorUtils.toList(index4.getCollectionIds().iterator());
    if (!(cids3.containsAll(cids4) && cids4.containsAll(cids3))) {
      fail(String.format("Expected both the original and rebuilt artifact indexes to contain the same set of collection IDs: %s vs %s", cids3, cids4));
    }

    // Iterate over the collection IDs
    for (String cid : cids3) {
      // Compare the set of AUIDs
      List<String> auids3 = IteratorUtils.toList(index3.getAuIds(cid).iterator());
      List<String> auids4 = IteratorUtils.toList(index4.getAuIds(cid).iterator());
      if (!(auids3.containsAll(auids4) && auids4.containsAll(auids3))) {
        fail("Expected both the original and rebuilt artifact indexes to contain the same set of AUIDs");
      }

      // Iterate over AUIDs
      for (String auid : auids3) {
        List<Artifact> artifacts3 = IteratorUtils.toList(index3.getAllArtifacts(cid, auid, true).iterator());
        List<Artifact> artifacts4 = IteratorUtils.toList(index4.getAllArtifacts(cid, auid, true).iterator());

        // Debugging
        artifacts3.forEach(artifact -> log.info(String.format("Artifact from artifacts3: %s", artifact)));
        artifacts4.forEach(artifact -> log.info(String.format("Artifact from artifacts4: %s", artifact)));

        if (!(artifacts3.containsAll(artifacts4) && artifacts4.containsAll(artifacts3))) {
          fail("Expected both the original and rebuilt artifact indexes to contain the same set of artifacts");
        }
      }
    }
  }

  /**
   * Tests WARC file sealing operation.
   *
   * @throws Exception
   */
  @Disabled
  @Test
  public void testWarcSealing() throws Exception {
    // Use a volatile artifact index with this data store
    ArtifactIndex index = new VolatileArtifactIndex();
    store.setArtifactIndex(index);

    // The WARC records for the two artifacts here end up being 782 bytes each.
    store.setThresholdWarcSize(512L);

    index.initArtifactIndex();
    store.initArtifactDataStore();

    // Setup repository paths relative to a base dir
    String auBaseDirPath = "/collections/coll1/au-" + DigestUtils.md5Hex("auid1");
    String auArtifactsWarcPath = auBaseDirPath + "/artifacts.warc";
    String auMetadataWarcPath = auBaseDirPath + "/lockss-repo.warc";
    String sealedWarcDirPath = "/sealed";

    // Check that the repository is in an clean initialized state
    assertFalse(pathExists(auBaseDirPath));
    assertFalse(pathExists(auArtifactsWarcPath));
    assertFalse(pathExists(auMetadataWarcPath));
    assertTrue(isDirectory(sealedWarcDirPath));

    // HTTP status (200 OK) for use volatile ArtifactData's we'll add to the repository
    StatusLine status200 = new BasicStatusLine(new ProtocolVersion("HTTP", 1,1), 200, "OK");

    // Create an artifact and add it to the data store
    ArtifactIdentifier ident1 = new ArtifactIdentifier("coll1", "auid1", "http://example.com/u1", 1);
    org.apache.commons.io.output.ByteArrayOutputStream baos1 = new org.apache.commons.io.output.ByteArrayOutputStream(150);
    for (int i = 0 ; i < 150 ; ++i) {
      baos1.write('a');
    }
    ArtifactData dat1 = new ArtifactData(ident1, null, baos1.toInputStream(), status200);
    Artifact art1 = store.addArtifactData(dat1);
    baos1.close(); // to satisfy static analyzers

    // Register the artifact in the index
    index.indexArtifact(dat1);
    store.commitArtifactData(art1);
    index.commitArtifact(art1.getId());
    assertNotNull(index.getArtifact(art1.getId()));

    // Directories for the AU should now exist
    assertTrue(isDirectory(auBaseDirPath));
//    assertTrue(isFile(auArtifactsWarcPath));
    assertTrue(isFile(auMetadataWarcPath));
    assertTrue(isDirectory(sealedWarcDirPath));

    // The storage URL of the artifact data should match the storage url returned by artifact representing the artifact
    // data, and it should be belong to the correct AU's WARC file.
    assertEquals(dat1.getStorageUrl(), art1.getStorageUrl());
//    assertThat(art1.getStorageUrl(), startsWith(store.makeStorageUrl(auArtifactsWarcPath)));

    // Add another artifact to the store - this will add another 782 bytes to the WARC file
    ArtifactIdentifier ident2 = new ArtifactIdentifier("coll1", "auid1", "http://example.com/u2", 1);
    org.apache.commons.io.output.ByteArrayOutputStream baos2 = new ByteArrayOutputStream(150);
    for (int i = 0 ; i < 150 ; ++i) {
      baos2.write('b');
    }
    ArtifactData dat2 = new ArtifactData(ident2, null, baos2.toInputStream(), status200);
    Artifact art2 = store.addArtifactData(dat2);
    baos2.close(); // to satisfy static analyzers

    assertEquals(dat2.getStorageUrl(), art2.getStorageUrl());
//    assertThat(art2.getStorageUrl(), startsWith(store.makeStorageUrl(auArtifactsWarcPath)));

    // Register the second artifact in the index
    index.indexArtifact(dat2);
    store.commitArtifactData(art2);
    index.commitArtifact(art2.getId());
    assertNotNull(index.getArtifact(art2.getId()));

    // Invoke a seal to WARC
//    Iterable<Artifact> sealedArtifactsIter = store.sealWarc("coll1", "auid1");

    // Invoking seal to WARC again without any more committed artifacts should result in no sealed artifacts
//    assertEmpty(store.sealWarc("coll1", "auid1"));

    // After a seal, both the AU directory and its default artifacts.warc should still exist
    assertTrue(isDirectory(auBaseDirPath));
//    assertTrue(pathExists(auArtifactsWarcPath));

    // TODO: What to do with the repository metadata? For now check that it's left in place
    assertTrue(isFile(auMetadataWarcPath));
    assertTrue(isDirectory(sealedWarcDirPath));

    // There should be two sealed artifacts because we had two committed (and unsealed) artifacts
//    List<Artifact> sealedArtifacts = IterableUtils.toList(sealedArtifactsIter);
//    assertEquals(2, sealedArtifacts.size());

    // Assert things about each sealed artifact...
    /*
    for (Artifact sealedArtifact : sealedArtifacts) {
      // ...the storage URL of a sealed artifact should be under the sealed WARCs path
      assertThat(sealedArtifact.getStorageUrl(), startsWith(store.makeStorageUrl(sealedWarcDirPath)));

      // ...check that the sealed WARC file exists
      Matcher mat = store.fileAndOffsetStorageUrlPat.matcher(sealedArtifact.getStorageUrl());
      assertTrue(mat.matches());
      String relativeWarcPath = mat.group(3);
      assertTrue(isFile(relativeWarcPath));

      // ...the index should reflect the new storage URL
      Artifact fromIndex = index.getArtifact(sealedArtifact.getId());
      assertNotNull(fromIndex);
      assertEquals(fromIndex.getStorageUrl(), sealedArtifact.getStorageUrl());
    }
    */

    // The storage URL for the first artifact should have been updated
    Artifact art1i = index.getArtifact(art1.getId());
    assertNotEquals(art1.getStorageUrl(), art1i.getStorageUrl());

    // The storage URL for the second artifact should have been updated
    Artifact art2i = index.getArtifact(art2.getId());
    assertNotEquals(art2.getStorageUrl(), art2i.getStorageUrl());

    // The storage URL for the third artifact should NOT have been updated (it was not committed)
//    Artifact art3i = index.getArtifact(art3.getId());
//    assertNotEquals(art3.getStorageUrl(), art3i.getStorageUrl());

  }
  
}
