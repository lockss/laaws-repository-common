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

import java.io.*;
import java.util.*;
import java.util.stream.*;
import java.util.function.*;

import org.apache.commons.io.*;
import org.apache.commons.lang3.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.lang3.builder.CompareToBuilder;
import org.apache.commons.lang3.tuple.*;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.http.ProtocolVersion;
import org.apache.http.StatusLine;
import org.apache.http.message.BasicStatusLine;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.provider.*;
import org.springframework.http.HttpHeaders;

import org.lockss.laaws.rs.model.Artifact;
import org.lockss.laaws.rs.model.ArtifactData;
import org.lockss.laaws.rs.model.ArtifactIdentifier;

import org.lockss.util.test.*;



// TODO:
//
// - test default methods in LockssRepository interface
// - multi-threaded (for local? & rest)
// - more realistic workflows (retrievals more interleaved with stores)
// - different headers
// - test persistence (shut down repo, recreate)

/** Test harness for LockssRepository implementations */
public abstract class AbstractLockssRepositoryTest extends LTC5 {

  public abstract LockssRepository makeLockssRepository() throws Exception;

  private final static Log log =
    LogFactory.getLog(AbstractLockssRepositoryTest.class);

  static int MAX_RANDOM_FILE = 50000;
  static int MAX_INCR_FILE = 20000;
//   static int MAX_RANDOM_FILE = 4000;
//   static int MAX_INCR_FILE = 4000;

  // TEST DATA

  // Commonly used artifact identifiers and contents
  static String COLL1 = "coll1";
  static String COLL2 = "coll2";
  static String AUID1 = "auid1";
  static String AUID2 = "auid2";
  static String ARTID1 = "art_id_1";

  static String URL1 = "http://host1.com/path";
  static String URL2 = "http://host2.com/file1";
  static String URL3 = "http://host2.com/file2";
  static String PREFIX1 = "http://host2.com/";

  static String CONTENT1 = "content string 1";

  static HttpHeaders HEADERS1 = new HttpHeaders();
  static {
    HEADERS1.set("key1", "val1");
    HEADERS1.set("key2", "val2");
  }

  private static StatusLine STATUS_LINE_OK =
    new BasicStatusLine(new ProtocolVersion("HTTP", 1,1), 200, "OK");
  private static StatusLine STATUS_LINE_MOVED =
    new BasicStatusLine(new ProtocolVersion("HTTP", 1,1), 301, "Moved");

  // Identifiers expected not to exist in the repository
  static String NO_COLL= "no_coll";
  static String NO_AUID = "no_auid";
  static String NO_URL = "no_url";
  static String NO_ARTID = "not an artifact ID";

  // Sets of coll, au, url.  Last one in each differs only in case from
  // previous, to check case-sensitivity
  static String[] COLLS = {COLL1, COLL2, "Coll2"};
  static String[] AUIDS = {AUID1, AUID2, "Auid2"};
  static String[] URLS = {URL1, URL2, URL2.toUpperCase()};

  // Definition of variants to run
  enum StdVariants {
    empty, commit1, uncommit1, url3, url3unc, disjoint, grid3x3x3,
  }

  public List<ArtSpec> getVariantSpecs(String variant) throws IOException {
    List<ArtSpec> res = new ArrayList<ArtSpec>();
    switch (variant) {
    case "empty":
      break;
    case "commit1":
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1).toCommit(true));
      break;
    case "uncommit1":
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1));
      break;
    case "url3":
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1).toCommit(true));
      break;
    case "url3unc":
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1));
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1).toCommit(true));

      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL2).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL2).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL2));
      break;
    case "disjoint":
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1));
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1).toCommit(true));

      res.add(ArtSpec.forCollAuUrl(COLL2, AUID2, URL2).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL2, AUID2, URL2).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL2, AUID2, URL2));
      break;
    case "overlap":
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1));
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL2).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL2));
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL2).toCommit(true));

      res.add(ArtSpec.forCollAuUrl(COLL2, AUID2, URL1).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL2, AUID2, URL1).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL2, AUID2, URL1));
      res.add(ArtSpec.forCollAuUrl(COLL2, AUID2, URL2).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL2, AUID2, URL2).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL2, AUID2, URL2));
      break;
    case "grid3x3x3":
      boolean toCommit = false;
      for (String coll : COLLS) {
	for (String auid : AUIDS) {
	  for (String url : URLS) {
	    res.add(ArtSpec.forCollAuUrl(coll, auid, url).toCommit(toCommit));
	    toCommit = !toCommit;
	  }
	}
      }
      break;
    }
    return res;
  }
  
  // LOCALS

  protected LockssRepository repository;

  // Currently running variant name
  private String variant = "no_variant";

  // ArtSpec for each Artifact that has been added to the repository
  List<ArtSpec> addedSpecs = new ArrayList<ArtSpec>();

  // Maps ArtButVer to ArtSpec for highest version added to the repository
  Map<String,ArtSpec> highestVerSpec = new HashMap<String,ArtSpec>();
  // Maps ArtButVer to ArtSpec for highest version added and committed to
  // the repository
  Map<String,ArtSpec> highestCommittedVerSpec = new HashMap<String,ArtSpec>();


  // SETUP

  @BeforeEach
  public void beforeEach() throws Exception {
    setUpRepo();
    beforeVariant();
  }

  void setUpRepo() throws Exception {
    this.repository = makeLockssRepository();
  }

  // Set up the current variant: create appropriate ArtSpecs and add them
  // to the repository
  void beforeVariant() throws IOException {
    List<ArtSpec> scenario = getVariantSpecs(variant);
    instantiateScanario(scenario);
  }

  // Add Artifacts to the repository as specified by the ArtSpecs
  void instantiateScanario(List<ArtSpec> scenario) throws IOException {
    for (ArtSpec spec : scenario) {
      Artifact art = addUncommitted(spec);
      if (spec.isDoCommit()) {
	commit(spec, art);
      }
    }      
  }


  @AfterEach
  public void tearDownArtifactDataStore() throws Exception {
    this.repository = null;
  }

  // Invoked automatically before each test by the @VariantTest mechanism
  @Override
  protected void setUpVariant(String variantName) {
    log.info("setUpVariant: " + variantName);
    variant = variantName;
  }

  // TESTS

  // write artifacts of increasing size, catch size-related bugs early
  @Test
  public void testArticleSizes() throws IOException {
    for (int size = 0; size < MAX_INCR_FILE; size += 100) {
      testArticleSize(size);
    }
  }

  public void testArticleSize(int size) throws IOException {
    ArtSpec spec = ArtSpec.forCollAuUrl(COLL1, AUID1, URL1 + size)
      .toCommit(true);
    Artifact newArt = addUncommitted(spec);
    Artifact commArt = commit(spec, newArt);
    assertData(spec, commArt);
  }

  // This is now redundant
  @Test
  void emptyRepo() throws IOException {
    checkEmptyAu(COLL1, AUID1);
  }

  void checkEmptyAu(String coll, String auid) throws IOException {
    assertEmpty(repository.getAuIds(coll));
    assertEmpty(repository.getCollectionIds());
    assertEmpty(repository.getAllArtifacts(coll, AUID1));

    assertNull(repository.getArtifact(coll, AUID1, URL1));

    assertEquals(0, (long)repository.auSize(coll, AUID1));
    assertFalse(repository.artifactExists(coll, ARTID1));

    assertEmpty(repository.getArtifactAllVersions(coll, AUID1, URL1));
  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testAddArtifact() throws IOException {
    // Illegal arguments
    assertThrowsMatch(IllegalArgumentException.class,
		      "ArtifactData",
		      () -> {repository.addArtifact(null);});

    // Illegal ArtifactData (at least one null field)
    for (ArtifactData illAd : nullPointerArtData) {
      assertThrows(NullPointerException.class,
		   () -> {repository.addArtifact(illAd);});
    }

    // legal use of addArtifact is tested in the normal course of setting
    // up variants, and by testArticleSizes(), but for the sake of
    // completeness ...

    ArtSpec spec = new ArtSpec().setUrl("https://mr/ed/").setContent(CONTENT1);
    Artifact newArt = addUncommitted(spec);
    Artifact commArt = commit(spec, newArt);
    assertData(spec, commArt);
  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testGetArtifact() throws IOException {
    // Illegal args
    assertThrowsMatch(IllegalArgumentException.class,
		      "collection",
		      () -> {repository.getArtifact(null, null, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "collection",
		      () -> {repository.getArtifact(null, AUID1, URL1);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "au",
		      () -> {repository.getArtifact(COLL1, null, URL1);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "url",
		      () -> {repository.getArtifact(COLL1, AUID1, null);});

    // Artifact not found
    for (ArtSpec spec : notFoundArtSpecs()) {
      log.info("s.b. notfound: " + spec);
      assertNull(getArtifact(repository, spec),
		 "Null or non-existent name shouldn't be found: " + spec);
    }

    // Ensure that a no-version retrieval gets the expects highest version
    for (ArtSpec highSpec : highestCommittedVerSpec.values()) {
      log.info("highSpec: " + highSpec);
      assertData(highSpec, repository.getArtifact(highSpec.getCollection(),
						  highSpec.getAuid(),
						  highSpec.getUrl()));
    }

  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testGetArtifactData() throws IOException {
    // Illegal args
    assertThrowsMatch(IllegalArgumentException.class,
		      "Null",
		      () -> {repository.getArtifactData(null, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "Null",
		      () -> {repository.getArtifactData(null, ARTID1);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "Null",
		      () -> {repository.getArtifactData(COLL1, null);});

    // Artifact not found
    assertNull(repository.getArtifactData(COLL1, NO_ARTID));

    ArtSpec cspec = anyCommittedSpec();
    if (cspec != null) {
      ArtifactData ad = repository.getArtifactData(cspec.getCollection(),
						   cspec.getArtifactId());
      assertData(cspec, ad);
    }
    ArtSpec uspec = anyUncommittedSpec();
    if (uspec != null) {
      ArtifactData ad = repository.getArtifactData(uspec.getCollection(),
						   uspec.getArtifactId());
      assertData(uspec, ad);
    }
  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testGetArtifactVersion() throws IOException {
    // Illegal args
    assertThrowsMatch(IllegalArgumentException.class,
		      "collection",
		      () -> {repository.getArtifactVersion(null, null, null, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "collection",
		      () -> {repository.getArtifactVersion(null, AUID1, URL1, 1);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "au",
		      () -> {repository.getArtifactVersion(COLL1, null, URL1, 1);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "url",
		      () -> {repository.getArtifactVersion(COLL1, AUID1, null, 1);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "version",
		      () -> {repository.getArtifactVersion(COLL1, AUID1, URL1, null);});
    // XXXAPI illegal version numbers
//     assertThrowsMatch(IllegalArgumentException.class,
// 		      "version",
// 		      () -> {repository.getArtifactVersion(COLL1, AUID1, URL1, -1);});
//     assertThrowsMatch(IllegalArgumentException.class,
// 		      "version",
// 		      () -> {repository.getArtifactVersion(COLL1, AUID1, URL1, 0);});

    // Artifact not found

    // notFoundArtSpecs() includes some that would be found with a
    // different version so can't use that here.

    for (ArtSpec spec : neverFoundArtSpecs) {
      log.info("s.b. notfound: " + spec);
      assertNull(getArtifactVersion(repository, spec, 1),
		 "Null or non-existent name shouldn't be found: " + spec);
      assertNull(getArtifactVersion(repository, spec, 2),
		 "Null or non-existent name shouldn't be found: " + spec);
    }

    // Get all added artifacts, check correctness
    for (ArtSpec spec : addedSpecs) {
      if (spec.isCommitted()) {
	log.info("s.b. data: " + spec);
	assertData(spec, getArtifact(repository, spec));
      } else {
	log.info("s.b. uncommitted: " + spec);
	assertNull(getArtifact(repository, spec),
		   "Uncommitted shouldn't be found: " + spec);
      }
      // XXXAPI illegal version numbers
      assertNull(getArtifactVersion(repository, spec, 0));
      assertNull(getArtifactVersion(repository, spec, -1));
    }    

    // Ensure that a non-existent version isn't found
    for (ArtSpec highSpec : highestVerSpec.values()) {
      log.info("highSpec: " + highSpec);
      assertNull(repository.getArtifactVersion(highSpec.getCollection(),
					       highSpec.getAuid(),
					       highSpec.getUrl(),
					       highSpec.getVersion() + 1));
    }
  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testArtifactExists() throws IOException {
    // Illegal args
    assertThrowsMatch(IllegalArgumentException.class,
		      "collection",
		      () -> {repository.artifactExists(null, ARTID1);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "artifact id",
		      () -> {repository.artifactExists(COLL1, null);});


    // s.b. true for all added artifacts, including uncommitted
    for (ArtSpec spec : addedSpecs) {
      assertTrue(repository.artifactExists(spec.getCollection(),
					   spec.getArtifactId()));
      // false if only collection or artifactId is correct
      // XXXAPI collection is ignored
//       assertFalse(repository.artifactExists(NO_COLL,
// 					    spec.getArtifactId()));
      assertFalse(repository.artifactExists(spec.getCollection(),
					    NO_ARTID));
    }    

    assertFalse(repository.artifactExists("NO_COLL", "NO_ARTID"));
  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testAuSize() throws IOException {
    // Illegal args
    assertThrowsMatch(IllegalArgumentException.class,
		      "collection",
		      () -> {repository.auSize(null, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "collection",
		      () -> {repository.auSize(null, AUID1);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "au",
		      () -> {repository.auSize(COLL1, null);});

    // non-existent AU
    assertEquals(0, (long)repository.auSize(COLL1, NO_AUID));

    // XXXBUG auSize
    // Calculate the expected size of each AU in each collection, compare
    // with auSize()
    for (String coll : addedCollections()) {
      for (String auid : addedAuids()) {
	long expSize = committedSpecStream()
	  .filter(s -> s.getAuid().equals(auid))
	  .filter(s -> s.getCollection().equals(coll))
	  .mapToLong(ArtSpec::getContentLength)
	  .sum();
	assertEquals(expSize, (long)repository.auSize(coll, auid));
      }
    }

  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testCommitArtifact() throws IOException {
    // Illegal args
    assertThrows(IllegalArgumentException.class,
		 () -> {repository.commitArtifact(null, null);});
    assertThrows(IllegalArgumentException.class,
		 () -> {repository.commitArtifact(null, ARTID1);});
    assertThrows(IllegalArgumentException.class,
		 () -> {repository.commitArtifact(COLL1, null);});

    // Commit already committed artifact
    ArtSpec commSpec = anyCommittedSpec();
    if (commSpec != null) {
      // Get the existing artifact
      Artifact commArt = getArtifact(repository, commSpec);
      // XXXAPI should this throw?
//       assertThrows(NullPointerException.class,
// 		   () -> {repository.commitArtifact(commSpec.getCollection(),
// 						    commSpec.getArtifactId());});
      Artifact dupArt = repository.commitArtifact(commSpec.getCollection(),
						  commSpec.getArtifactId());
      assertEquals(commArt, dupArt);
      assertData(commSpec, dupArt);
    }
  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testDeleteArtifact() throws IOException {
    // Illegal args
    assertThrowsMatch(IllegalArgumentException.class,
		      "Null collection id or artifact id",
		      () -> {repository.deleteArtifact(null, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "artifact",
		      () -> {repository.deleteArtifact(COLL1, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "collection",
		      () -> {repository.deleteArtifact(null, AUID1);});

    // Delete non-existent artifact
    // XXXAPI
    assertThrowsMatch(IllegalArgumentException.class,
		      "Non-existent artifact id: " + AUID1,
		      () -> {repository.deleteArtifact(NO_COLL, AUID1);});

    // XXXBUG auSize
    // Delete a committed artifact, it should disappear and size should change
    ArtSpec spec = anyCommittedSpec();
    if (spec != null) {
      long totsize = repository.auSize(spec.getCollection(), spec.getAuid());
      long artsize = spec.getContentLength();
      assertTrue(repository.artifactExists(spec.getCollection(),
					   spec.getArtifactId()));
      assertNotNull(getArtifact(repository, spec));
      log.info("Deleting: " + spec);
      repository.deleteArtifact(spec.getCollection(), spec.getArtifactId());
      assertFalse(repository.artifactExists(spec.getCollection(),
					    spec.getArtifactId()));
      assertNull(getArtifact(repository, spec));
      assertEquals(totsize - artsize,
		   (long)repository.auSize(spec.getCollection(),
					   spec.getAuid()));
    // Delete an uncommitted artifact, it should disappear and size should
    // not change
    }
    ArtSpec uspec = anyUncommittedSpec();
    if (uspec != null) {
      long totsize = repository.auSize(uspec.getCollection(), uspec.getAuid());
      long artsize = uspec.getContentLength();
      assertTrue(repository.artifactExists(uspec.getCollection(),
					   uspec.getArtifactId()));
      assertNull(getArtifact(repository, uspec));
      log.info("Deleting: " + uspec);
      repository.deleteArtifact(uspec.getCollection(), uspec.getArtifactId());
      assertFalse(repository.artifactExists(uspec.getCollection(),
					    uspec.getArtifactId()));
      assertNull(getArtifact(repository, uspec));
      assertEquals(totsize,
		   (long)repository.auSize(uspec.getCollection(),
					   uspec.getAuid()));
    }

    // TK Delete committed & uncommitted arts & check results each time
    // delete twice
    // check getAuIds() & getCollectionIds() as they run out

  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testGetAllArtifacts() throws IOException {
    // Illegal args
    assertThrowsMatch(IllegalArgumentException.class,
		      "Null collection id or au id",
		      () -> {repository.getAllArtifacts(null, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "au",
		      () -> {repository.getAllArtifacts(COLL1, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "collection",
		      () -> {repository.getAllArtifacts(null, AUID1);});

    // Non-existent collection & auid
    assertEmpty(repository.getAllArtifacts(NO_COLL, NO_AUID));

    String anyColl = null;
    String anyAuid = null;

    // Compare with all URLs in each AU
    for (String coll : addedCollections()) {
      anyColl = coll;
      for (String auid : addedAuids()) {
	anyAuid = auid;
	assertArtList((orderedAllAu(coll, auid)
		       .filter(distinctByKey(ArtSpec::artButVerKey))),
		      repository.getAllArtifacts(coll, auid));
	
      }
    }

    // Combination of coll and au id that both exist, but have no artifacts
    // in common
    Pair<String,String> collau = collAuMistmatch();
    if (collau != null) {
      assertEmpty(repository.getAllArtifacts(collau.getLeft(),
					     collau.getRight()));
    }
    // non-existent coll, au
    if (anyColl != null && anyAuid != null) {
      assertEmpty(repository.getAllArtifacts(anyColl,
					     anyAuid + "_notAuSuffix"));
      assertEmpty(repository.getAllArtifacts(anyColl + "_notCollSuffix",
					     anyAuid));
    }    
  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testGetAllArtifactsWithPrefix() throws IOException {
    // Illegal args
    assertThrowsMatch(IllegalArgumentException.class,
		      "Null collection id, au id or prefix",
		      () -> {repository.getAllArtifactsWithPrefix(null, null, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "prefix",
		      () -> {repository.getAllArtifactsWithPrefix(COLL1, AUID1, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "au",
		      () -> {repository.getAllArtifactsWithPrefix(COLL1, null, PREFIX1);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "collection",
		      () -> {repository.getAllArtifactsWithPrefix(null, AUID1, PREFIX1);});

    // Non-existent collection & auid
    assertEmpty(repository.getAllArtifactsWithPrefix(NO_COLL, NO_AUID, PREFIX1));
    // Compare with all URLs matching prefix in each AU
    for (String coll : addedCollections()) {
      for (String auid : addedAuids()) {
	assertArtList((orderedAllAu(coll, auid)
		       .filter(spec -> spec.getUrl().startsWith(PREFIX1))
		       .filter(distinctByKey(ArtSpec::artButVerKey))),
		       repository.getAllArtifactsWithPrefix(coll, auid, PREFIX1));
	assertEmpty(repository.getAllArtifactsWithPrefix(coll, auid,
							 PREFIX1 + "notpath"));
      }
    }

    // Combination of coll and au id that both exist, but have no artifacts
    // in common
    Pair<String,String> collau = collAuMistmatch();
    if (collau != null) {
      assertEmpty(repository.getAllArtifactsWithPrefix(collau.getLeft(),
						       collau.getRight(),
						       PREFIX1));
    }
  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testGetAllArtifactsAllVersions() throws IOException {
    // Illegal args
    assertThrowsMatch(IllegalArgumentException.class,
		      "Null collection id or au id",
		      () -> {repository.getAllArtifactsAllVersions(null, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "au",
		      () -> {repository.getAllArtifactsAllVersions(COLL1, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "collection",
		      () -> {repository.getAllArtifactsAllVersions(null, AUID1);});

    // Non-existent collection & auid
    assertEmpty(repository.getAllArtifactsAllVersions(NO_COLL, NO_AUID));

    String anyColl = null;
    String anyAuid = null;
    // Compare with all URLs all version in each AU
    for (String coll : addedCollections()) {
      anyColl = coll;
      for (String auid : addedAuids()) {
	anyAuid = auid;
	assertArtList(orderedAllAu(coll, auid),
		      repository.getAllArtifactsAllVersions(coll, auid));
	
      }
    }
    // Combination of coll and au id that both exist, but have no artifacts
    // in common
    Pair<String,String> collau = collAuMistmatch();
    if (collau != null) {
      assertEmpty(repository.getAllArtifactsAllVersions(collau.getLeft(),
							collau.getRight()));
    }
    if (anyColl != null && anyAuid != null) {
      assertEmpty(repository.getAllArtifactsAllVersions(anyColl,
							anyAuid + "_not"));
      assertEmpty(repository.getAllArtifactsAllVersions(anyColl + "_not",
							anyAuid));
    }    
  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testGetAllArtifactsWithPrefixAllVersions() throws IOException {
    // Illegal args
    assertThrowsMatch(IllegalArgumentException.class,
		      "Null collection id, au id or prefix",
		      () -> {repository.getAllArtifactsWithPrefixAllVersions(null, null, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "prefix",
		      () -> {repository.getAllArtifactsWithPrefixAllVersions(COLL1, AUID1, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "au",
		      () -> {repository.getAllArtifactsWithPrefixAllVersions(COLL1, null, PREFIX1);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "collection",
		      () -> {repository.getAllArtifactsWithPrefixAllVersions(null, AUID1, PREFIX1);});

    // Non-existent collection & auid
    assertEmpty(repository.getAllArtifactsWithPrefixAllVersions(NO_COLL, NO_AUID, PREFIX1));
    // Compare with all URLs matching prefix in each AU
    for (String coll : addedCollections()) {
      for (String auid : addedAuids()) {
	assertArtList((orderedAllAu(coll, auid)
		       .filter(spec -> spec.getUrl().startsWith(PREFIX1))),
		       repository.getAllArtifactsWithPrefixAllVersions(coll, auid, PREFIX1));
	assertEmpty(repository.getAllArtifactsWithPrefixAllVersions(coll, auid,
								    PREFIX1 + "notpath"));
      }
    }

    // Combination of coll and au id that both exist, but have no artifacts
    // in common
    Pair<String,String> collau = collAuMistmatch();
    if (collau != null) {
      assertEmpty(repository.getAllArtifactsWithPrefixAllVersions(collau.getLeft(),
						       collau.getRight(),
						       PREFIX1));
    }
  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testGetArtifactAllVersions() throws IOException {
    // Illegal args
    assertThrowsMatch(IllegalArgumentException.class,
		      "Null collection id, au id or url",
		      () -> {repository.getArtifactAllVersions(null, null, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "url",
		      () -> {repository.getArtifactAllVersions(COLL1, AUID1, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "au",
		      () -> {repository.getArtifactAllVersions(COLL1, null, URL1);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "coll",
		      () -> {repository.getArtifactAllVersions(null, AUID1, URL1);});

    // Non-existent collection, auid or url
    assertEmpty(repository.getArtifactAllVersions(NO_COLL, AUID1, URL1));
    assertEmpty(repository.getArtifactAllVersions(COLL1, NO_AUID, URL1));
    assertEmpty(repository.getArtifactAllVersions(COLL1, AUID1, NO_URL));

    // For each ArtButVer in the repository, enumerate all its versions and
    // compare with expected
    Stream<ArtSpec> s =
      committedSpecStream().filter(distinctByKey(ArtSpec::artButVerKey));
    for (ArtSpec urlSpec : (Iterable<ArtSpec>)s::iterator) {
      assertArtList(orderedAll()
		    .filter(spec -> spec.sameArtButVer(urlSpec)),
		    repository.getArtifactAllVersions(urlSpec.getCollection(),
						      urlSpec.getAuid(),
						      urlSpec.getUrl()));
    }
  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testGetAuIds() throws IOException {
    // Illegal args
    assertThrowsMatch(IllegalArgumentException.class,
		      "Null collection",
		      () -> {repository.getAuIds(null);});

    // Non-existent collection
    assertEmpty(repository.getAuIds(NO_COLL));

    // Compare with expected auid list for each collection
    for (String coll : addedCollections()) {
      Iterator<String> expAuids =
	orderedAllColl(coll)
	.map(ArtSpec::getAuid)
	.distinct()
	.iterator();
      assertEquals(IteratorUtils.toList(expAuids),
		   IteratorUtils.toList(repository.getAuIds(coll).iterator()));
    }

    // Try getAuIds() on collections that have no committed artifacts

    // All the collection ids in the repo
    List<String> allCollections = addedSpecStream()
      .map(ArtSpec::getCollection)
      .distinct()
      .collect(Collectors.toList());

    // All the collection ids that have committed artifacts
    List<String> collectionsWithCommittedArt = orderedAll()
      .map(ArtSpec::getCollection)
      .distinct()
      .collect(Collectors.toList());

    for (String coll : CollectionUtils.subtract(allCollections,
						collectionsWithCommittedArt)) {
      assertEmpty(repository.getAuIds(coll));
    }
  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testGetCollectionIds() throws IOException {
    Iterator<String> expColl =
      orderedAll()
      .map(ArtSpec::getCollection)
      .distinct()
      .iterator();
      assertEquals(IteratorUtils.toList(expColl),
		   IteratorUtils.toList(repository.getCollectionIds().iterator()));
  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testIsArtifactCommitted() throws IOException {
    // Illegal args
    assertThrowsMatch(IllegalArgumentException.class,
		      "Null collection id or artifact id",
		      () -> {repository.isArtifactCommitted(null, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "artifact",
		      () -> {repository.isArtifactCommitted(COLL1, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "collection",
		      () -> {repository.isArtifactCommitted(null, ARTID1);});

    // non-existent collection, artifact id

    // XXXAPI
    assertThrowsMatch(IllegalArgumentException.class,
		      "Non-existent artifact id: " + NO_ARTID,
		      () -> {repository.isArtifactCommitted(COLL1, NO_ARTID);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "Non-existent artifact id: " + ARTID1,
		      () -> {repository.isArtifactCommitted(NO_COLL, ARTID1);});

//     assertFalse(repository.isArtifactCommitted(COLL1, NO_ARTID));
//     assertFalse(repository.isArtifactCommitted(NO_COLL, ARTID1));

    for (ArtSpec spec : addedSpecs) {
      if (spec.isCommitted()) {
	assertTrue(repository.isArtifactCommitted(spec.getCollection(),
						  spec.getArtifactId()));
      } else {
	assertFalse(repository.isArtifactCommitted(spec.getCollection(),
						   spec.getArtifactId()));
      }
    }

  }


  // Assertions

    void assertData(ArtSpec spec, Artifact art) throws IOException {
    assertNotNull(art, "Comparing with " + spec);
    assertEquals(spec.getCollection(), art.getCollection());
    assertEquals(spec.getAuid(), art.getAuid());
    assertEquals(spec.getUrl(), art.getUri());
    if (spec.getExpVer() >= 0) {
      assertEquals(spec.getExpVer(), (int)art.getVersion());
    }
    ArtifactData ad = repository.getArtifactData(art);
    assertEquals(art.getIdentifier(), ad.getIdentifier());
    assertEquals(spec.getContentLength(), ad.getContentLength());
    assertData(spec, ad);

    ArtifactData ad2 = repository.getArtifactData(spec.getCollection(),
						  art.getId());
    assertEquals(spec.getContentLength(), ad2.getContentLength());
    assertData(spec, ad2);
  }

  void assertEquals(StatusLine exp, StatusLine line) {
    assertEquals(exp.toString(), line.toString());
  }

  void assertData(ArtSpec spec, ArtifactData ad) throws IOException {
    assertEquals(spec.getStatusLine(), ad.getHttpStatus());
    assertEquals(spec.getContentLength(), ad.getContentLength());
    assertSameBytes(spec.getInputStream(), ad.getInputStream(),
		    spec.getContentLength());
    assertEquals(spec.getHeaders(),
		 RepoUtil.mapFromHttpHeaders(ad.getMetadata()));
  }


  void assertArtList(Stream<ArtSpec> expSpecs, Iterable<Artifact> arts)
      throws IOException {
    Iterator<ArtSpec> specIter = expSpecs.iterator();
    Iterator<Artifact> artIter = arts.iterator();
    while (specIter.hasNext() && artIter.hasNext()) {
      ArtSpec spec = specIter.next();
      Artifact art = artIter.next();
      assertData(spec, art);
    }
    assertFalse(specIter.hasNext());
    assertFalse(artIter.hasNext());
  }




  // utilities


  void logAdded() {
    for (ArtSpec spec : addedSpecs) {
      log.info("spec: " + spec);
    }
  }

  long expectedVersions(ArtSpec spec) {
    return addedSpecs.stream()
      .filter(s -> spec.sameArtButVer(s))
      .count();
  }

  List<String> addedAuids() {
    return addedSpecs.stream()
      .map(ArtSpec::getAuid)
      .distinct()
      .collect(Collectors.toList());
  }

  List<String> addedCommittedAuids() {
    return addedSpecs.stream()
      .filter(spec -> spec.isCommitted())
      .map(ArtSpec::getAuid)
      .distinct()
      .collect(Collectors.toList());
  }

  List<String> addedCollections() {
    return addedSpecs.stream()
      .map(ArtSpec::getCollection)
      .distinct()
      .collect(Collectors.toList());
  }

  List<String> addedCommittedCollections() {
    return addedSpecs.stream()
      .filter(spec -> spec.isCommitted())
      .map(ArtSpec::getCollection)
      .distinct()
      .collect(Collectors.toList());
  }

  Stream<String> collectionsOf(Stream<ArtSpec> specStream) {
    return specStream
      .map(ArtSpec::getCollection)
      .distinct();
  }

  Stream<String> auidsOf(Stream<ArtSpec> specStream, String collection) {
    return specStream
      .filter(s -> s.getCollection().equals(collection))
      .map(ArtSpec::getAuid)
      .distinct();
  }

  Stream<ArtSpec> addedSpecStream() {
    return addedSpecs.stream();
  }

  Stream<ArtSpec> committedSpecStream() {
    return addedSpecs.stream()
      .filter(spec -> spec.isCommitted());
  }

  Stream<ArtSpec> uncommittedSpecStream() {
    return addedSpecs.stream()
      .filter(spec -> !spec.isCommitted());
  }

  Stream<ArtSpec> orderedAll() {
    return committedSpecStream()
      .sorted();
  }

  public static <T> Predicate<T>
    distinctByKey(Function<? super T,Object> keyExtractor) {
    Set seen = new HashSet();
    return t -> seen.add(keyExtractor.apply(t));
  }

  Stream<ArtSpec> orderedAllColl(String coll) {
    return committedSpecStream()
      .filter(s -> s.getCollection().equals(coll))
      .sorted();
  }

  Stream<ArtSpec> orderedAllAu(String coll, String auid) {
    return committedSpecStream()
      .filter(s -> s.getCollection().equals(coll))
      .filter(s -> s.getAuid().equals(auid))
      .sorted();
  }

  Stream<ArtSpec> orderedAllUrl(String coll, String auid, String url) {
    return committedSpecStream()
      .filter(s -> s.getCollection().equals(coll))
      .filter(s -> s.getAuid().equals(auid))
      .filter(s -> s.getUrl().equals(url))
      .sorted();
  }

  ArtSpec anyCommittedSpec() {
    return committedSpecStream().findAny().orElse(null);
  }

  ArtSpec anyUncommittedSpec() {
    return uncommittedSpecStream().findAny().orElse(null);
  }


  // Find a collection and an au that each have artifacts, but don't have
  // any artifacts in common
  Pair<String,String> collAuMistmatch() {
    Set<Pair<String,String>> set = new HashSet<Pair<String,String>>();
    for (String coll : addedCommittedCollections()) {
      for (String auid : addedCommittedAuids()) {
	set.add(new ImmutablePair(coll, auid));
      }
    }
    committedSpecStream()
      .forEach(spec -> {set.remove(new ImmutablePair(spec.getCollection(),
						     spec.getAuid()));});
    if (set.isEmpty()) {
      return null;
    } else {
      Pair<String,String> res = set.iterator().next();
      log.info("Found coll au mismatch: " +
	       res.getLeft() + ", " + res.getRight());
      logAdded();
      return res;
    }
  }
    
  Artifact getArtifact(LockssRepository repository, ArtSpec spec)
      throws IOException {
    log.info(String.format("getArtifact(%s, %s, %s)",
			   spec.getCollection(),
			   spec.getAuid(),
			   spec.getUrl()));
    if (spec.hasVersion()) {
      return repository.getArtifactVersion(spec.getCollection(),
					   spec.getAuid(),
					   spec.getUrl(),
					   spec.getVersion());
    } else {
      return repository.getArtifact(spec.getCollection(),
				    spec.getAuid(),
				    spec.getUrl());
    }
  }

  Artifact getArtifactVersion(LockssRepository repository, ArtSpec spec,
			      int ver)
      throws IOException {
    log.info(String.format("getArtifactVersion(%s, %s, %s, %d)",
			   spec.getCollection(),
			   spec.getAuid(),
			   spec.getUrl(),
			   ver));
    return repository.getArtifactVersion(spec.getCollection(),
					 spec.getAuid(),
					 spec.getUrl(),
					 ver);
  }

  Artifact addUncommitted(ArtSpec spec) throws IOException {
    if (!spec.hasContent()) {
      spec.setContent(RandomStringUtils.randomAlphabetic(0, MAX_RANDOM_FILE));
      log.info("gen content");
    }
    log.info("adding: " + spec);
    
    ArtifactData ad = spec.getArtifactData();
    Artifact newArt = repository.addArtifact(ad);
    assertNotNull(newArt);

//     assertData(spec, newArt);
    long expVers = expectedVersions(spec);
    assertEquals(expVers + 1, (int)newArt.getVersion(),
		 "version of " + newArt);
    if (spec.getExpVer() >= 0) {
      throw new IllegalStateException("addUncommitted() must be called with unused ArtSpec");
    }

    String newArtId = newArt.getId();
    assertNotNull(newArtId);
    assertFalse(repository.isArtifactCommitted(spec.getCollection(),
					       newArtId));
    assertFalse(newArt.getCommitted());
    assertTrue(repository.artifactExists(spec.getCollection(), newArtId));

    Artifact oldArt = getArtifact(repository, spec);
    if (expVers > 0) {
      // this test valid only when a single version exists
//       assertData(spec, oldArt);
      
    } else {
      assertNull(oldArt);
    }
    spec.setVersion(newArt.getVersion());
    spec.setArtifactId(newArtId);

    addedSpecs.add(spec);
    // Remember the highest version of this URL we've added
    ArtSpec maxVerSpec = highestVerSpec.get(spec.artButVerKey());
    if (maxVerSpec == null || maxVerSpec.getVersion() < spec.getVersion()) {
      highestVerSpec.put(spec.artButVerKey(), spec);
    }
    return newArt;
  }

  Artifact commit(ArtSpec spec, Artifact art) throws IOException {
    String artId = art.getId();
    Artifact commArt = repository.commitArtifact(spec.getCollection(), artId);
    if (spec.getExpVer() > 0) {
      assertEquals(spec.getExpVer(), (int)commArt.getVersion());
    }
    spec.setCommitted(true);
    // Remember the highest version of this URL we've committed
    ArtSpec maxVerSpec = highestCommittedVerSpec.get(spec.artButVerKey());
    if (maxVerSpec == null || maxVerSpec.getVersion() < spec.getVersion()) {
      highestCommittedVerSpec.put(spec.artButVerKey(), spec);
    }
    assertNotNull(commArt);
    assertTrue(repository.isArtifactCommitted(spec.getCollection(),
					      commArt.getId()));
    assertTrue(commArt.getCommitted());

    assertData(spec, commArt);

    Artifact newArt = getArtifact(repository, spec);
    assertNotNull(newArt);
    assertTrue(repository.isArtifactCommitted(spec.getCollection(),
					      newArt.getId()));
    assertTrue(newArt.getCommitted());
    assertTrue(repository.artifactExists(spec.getCollection(), newArt.getId()));
    return newArt;
  }

  // These should all cause addArtifact to throw NPE 
  ArtifactData[] nullPointerArtData = {
    new ArtifactData(null, null, null),
    new ArtifactData(null, null, STATUS_LINE_OK), 
    new ArtifactData(null, stringInputStream(""), null),
    new ArtifactData(null, stringInputStream(""), STATUS_LINE_OK), 
    new ArtifactData(HEADERS1, null, null),
    new ArtifactData(HEADERS1, null, STATUS_LINE_OK), 
    new ArtifactData(HEADERS1, stringInputStream(""), null), 
  };    

  // These describe artifacts that getArtifact() should never find
  ArtSpec[] neverFoundArtSpecs = {
    ArtSpec.forCollAuUrl(NO_COLL, AUID1, URL1),
    ArtSpec.forCollAuUrl(COLL1, NO_AUID, URL1),
    ArtSpec.forCollAuUrl(COLL1, AUID1, NO_URL),
  };    

  /** Return list of ArtSpecs that shouldn't be found in the current
   * repository */
  List<ArtSpec> notFoundArtSpecs() {
    List<ArtSpec> res = new ArrayList<ArtSpec>();
    // Always include some that should never be found
    Collections.addAll(res, neverFoundArtSpecs);

    // Include an uncommitted artifact, if any
    ArtSpec uncSpec = anyUncommittedSpec();
    if (uncSpec != null) {
      log.info("adding an uncommitted spec: " + uncSpec);
      res.add(uncSpec);
    }
    
    // If there's at least one committed artifact ...
    ArtSpec commSpec = anyCommittedSpec();
    if (commSpec != null) {
      // include variants of it with non-existent collection, au, etc.
      res.add(commSpec.copy().setCollection("NO_" + commSpec.getCollection()));
      res.add(commSpec.copy().setAuid("NO_" + commSpec.getAuid()));
      res.add(commSpec.copy().setUrl("NO_" + commSpec.getUrl()));

      // and with existing but different collection, au
      ArtSpec differentColl = committedSpecStream()
	.filter(s -> !s.getCollection().equals(commSpec.getCollection()))
	.findAny().orElse(null);
      if (differentColl != null) {
	ArtSpec dcspec =
	  commSpec.copy().setCollection(differentColl.getCollection());
	log.info("adding a different collection spec: " + dcspec);
	res.add(dcspec);
      }
      ArtSpec differentAu = committedSpecStream()
	.filter(s -> !s.getAuid().equals(commSpec.getAuid()))
	.findAny().orElse(null);
      if (differentAu != null) {
	ArtSpec daspec = commSpec.copy().setAuid(differentAu.getAuid());
	log.info("adding a different au spec: " + daspec);
	res.add(daspec);
      }

      // and with correct coll, au, url but non-existent version
      res.add(commSpec.copy().setVersion(0));
      res.add(commSpec.copy().setVersion(1000));
    }

    return res;
  }

  InputStream stringInputStream(String str) {
    return IOUtils.toInputStream(str);
  }


  // All the info needed to create and store an Artifact, or to compare
  // with a retrieved Artifact
  static class ArtSpec implements Comparable {
    // Identifying fields used in lookups
    String coll = COLL1;
    String auid = AUID1;
    String url;
    int fixedVer = -1;

    // used for creation and comparison of actual with expected
    boolean toCommit = false;
    StatusLine statLine = STATUS_LINE_OK;
    Map<String,String> headers = RepoUtil.mapFromHttpHeaders(HEADERS1);
    String content;
    InputStream iStream;

    // expected values
    long len = -1;
    int expVer = -1;

    // state
    boolean isCommitted = false;
    String artId;
    
    ArtSpec copy() {
      return new ArtSpec().forCollAuUrl(coll, auid, url)
	.setStatusLine(getStatusLine())
	.setHeaders(new HashMap<String,String>(getHeaders()))
	.setContent(getContent())
	.setContentLength(len);
    }

    static ArtSpec forCollAuUrl(String coll, String auid, String url) {
      return new ArtSpec()
	.setCollection(coll)
	.setAuid(auid)
	.setUrl(url);
    }

    static ArtSpec forCollAuUrlVer(String coll, String auid,
				   String url, int version) {
      return ArtSpec.forCollAuUrl(coll, auid, url).setVersion(version);
    }

    ArtSpec setUrl(String url) {
      this.url = url;
      return this;
    }
    
    ArtSpec setExpVer(int ver) {
      this.expVer = ver;
      return this;
    }
    
    ArtSpec setCollection(String coll) {
      this.coll = coll;
      return this;
    }
    
    ArtSpec setAuid(String auid) {
      this.auid = auid;
      return this;
    }
    
    ArtSpec setContent(String content) {
      this.content = content;
      return this;
    }
    
    ArtSpec setVersion(int version) {
      this.fixedVer = version;
      return this;
    }
    
    ArtSpec setArtifactId(String id) {
      this.artId = id;
      return this;
    }
    
    ArtSpec setContentLength(long len) {
      this.len = len;
      return this;
    }
    
    ArtSpec setHeaders(Map headers) {
      this.headers = headers;
      return this;
    }

    ArtSpec setStatusLine(StatusLine statLine) {
      this.statLine = statLine;
      return this;
    }

    ArtSpec toCommit(boolean toCommit) {
      this.toCommit = toCommit;
      return this;
    }

    boolean isDoCommit() {
      return toCommit;
    }

    ArtSpec setCommitted(boolean committed) {
      this.isCommitted = committed;
      return this;
    }

    boolean isCommitted() {
      return isCommitted;
    }

    String getUrl() {
      return url;
    }
    
    String getCollection() {
      return coll;
    }
    
    String getAuid() {
      return auid;
    }
    
    int getVersion() {
      return fixedVer;
    }
    
    boolean hasVersion() {
      return fixedVer >= 0;
    }
    
    int getExpVer() {
      return expVer;
    }
    
    String getArtifactId() {
      return artId;
    }
    
    boolean hasContent() {
      return content != null || iStream != null;
    }

    String getContent() {
      return content;
    }
    
    long getContentLength() {
      if (len >= 0) {
	return len;
      } else if (content != null) {
	return content.length();
      } else {
	throw new IllegalStateException("getContentLen() called when length unknown");
      }
    }
    
    Map getHeaders() {
      return headers;
    }

    StatusLine getStatusLine() {
      return statLine;
    }

    HttpHeaders getMetdata() {
      return RepoUtil.httpHeadersFromMap(headers);
    }      

    ArtifactIdentifier getArtifactIdentifier() {
      return new ArtifactIdentifier(coll, auid, url, -1);
    }

    ArtifactData getArtifactData() {
      return new ArtifactData(getArtifactIdentifier(), getMetdata(),
			      getInputStream(), getStatusLine());
    }

    InputStream getInputStream() {
      if (content != null) {
	return IOUtils.toInputStream(content);
      }
      return null;
    }

    /** Order agrees with repository enumeration order: collection, auid,
     * url, version high-to-low */
    public int compareTo(Object o) {
      ArtSpec s = (ArtSpec)o;
      return new CompareToBuilder()
	.append(this.getCollection(), s.getCollection())
	.append(this.getAuid(), s.getAuid())
	.append(this.getUrl(), s.getUrl())
	.append(s.getVersion(), this.getVersion())
	.toComparison();
    }

    /** Return a key that's unique to the collection,au,url */
    public String artButVerKey() {
      return getCollection() + "|" + getAuid() + "|" + getUrl();
    }

    /** true if other refers to an artifact with the same collection, auid
     * and url, independent of version. */
    public boolean sameArtButVer(ArtSpec other) {
      return artButVerKey().equals(other.artButVerKey());
    }

    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(String.format("[ArtSpec: (%s,%s,%s,%d)", url, coll, auid, fixedVer));
      if (isCommitted()) {
	sb.append("C");
      }
      if (hasContent()) {
	sb.append(String.format(", len: %s", getContentLength()));
// 	sb.append(String.format(", len: %s, content: %.30s",
// 				getContentLength(), getContent()));
      }
      sb.append("]");
      return sb.toString();
    }
  }

}
