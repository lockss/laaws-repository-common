/*

Copyright (c) 2000-2019, Board of Trustees of Leland Stanford Jr. University,
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

package org.lockss.laaws.rs.core;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.*;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.collections4.*;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.builder.CompareToBuilder;
import org.apache.commons.lang3.tuple.*;
import org.apache.http.*;
import org.apache.http.message.BasicStatusLine;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.provider.EnumSource;
import org.lockss.laaws.rs.model.*;
import org.lockss.log.L4JLogger;
import org.lockss.util.PreOrderComparator;
import org.lockss.util.test.*;
import org.springframework.http.HttpHeaders;



// TODO:
//
// - test default methods in LockssRepository interface
// - multi-threaded (for local? & rest)
// - more realistic workflows (retrievals more interleaved with stores)
// - different headers
// - test persistence (shut down repo, recreate)

/** Test harness for LockssRepository implementations */
public abstract class AbstractLockssRepositoryTest extends LockssTestCase5 {

  /** Concrete subclasses must implement to create an instance of the
   * appropriate repository type */
  public abstract LockssRepository makeLockssRepository() throws Exception;

  private final static L4JLogger log = L4JLogger.getLogger();

  static boolean AVOID_STREAM_CLOSED_BUG = false;

  protected static int MAX_RANDOM_FILE = 50000;
  protected static int MAX_INCR_FILE = 20000;
  static {
    if (AVOID_STREAM_CLOSED_BUG) {
      // avoid Stream Closed bug by staying under 4096
      MAX_RANDOM_FILE = 4000;
      MAX_INCR_FILE = 4000;
    }
  }

  // TEST DATA

  // Commonly used artifact identifiers and contents
  protected static String COLL1 = "coll1";
  protected static String COLL2 = "coll2";
  protected static String AUID1 = "auid1";
  protected static String AUID2 = "auid2";
  protected static String ARTID1 = "art_id_1";

  protected static String URL1 = "http://host1.com/path";
  protected static String URL2 = "http://host2.com/file1";
  protected static String URL3 = "http://host2.com/file2";
  protected static String PREFIX1 = "http://host2.com/";

  protected static String CONTENT1 = "content string 1";

  protected static HttpHeaders HEADERS1 = new HttpHeaders();
  static {
    HEADERS1.set("key1", "val1");
    HEADERS1.set("key2", "val2");
  }

  protected static StatusLine STATUS_LINE_OK =
    new BasicStatusLine(new ProtocolVersion("HTTP", 1,1), 200, "OK");
  protected static StatusLine STATUS_LINE_MOVED =
    new BasicStatusLine(new ProtocolVersion("HTTP", 1,1), 301, "Moved");

  // Identifiers expected not to exist in the repository
  protected static String NO_COLL= "no_coll";
  protected static String NO_AUID = "no_auid";
  protected static String NO_URL = "no_url";
  protected static String NO_ARTID = "not an artifact ID";

  // Sets of coll, au, url for combinatoric tests.  Last one in each
  // differs only in case from previous, to check case-sensitivity
  protected static String[] COLLS = {COLL1, COLL2, "Coll2"};
  protected static String[] AUIDS = {AUID1, AUID2, "Auid2"};
  protected static String[] URLS = {URL1, URL2, URL2.toUpperCase()};

  // Comparators across AUs.
  protected static final Comparator<ArtSpec> BY_DATE_BY_AUID_BY_DECREASING_VERSION =
      Comparator.comparing(ArtSpec::getOriginDate)
                .thenComparing(ArtSpec::getAuid)
                .thenComparing(Comparator.comparingInt(ArtSpec::getVersion).reversed());

  protected static final Comparator<ArtSpec> BY_URI_BY_DATE_BY_AUID_BY_DECREASING_VERSION =
      Comparator.comparing(ArtSpec::getUrl, PreOrderComparator.INSTANCE)
                .thenComparing(ArtSpec::getOriginDate)
                .thenComparing(ArtSpec::getAuid)
                .thenComparing(Comparator.comparingInt(ArtSpec::getVersion).reversed());

  // Definition of variants to run
  protected enum StdVariants {
    empty, commit1, uncommit1, url3, url3unc, disjoint,
    grid3x3x3, grid3x3x3x3,
  }

  /** Return a list of ArtSpecs for the initial conditions for the named
   * variant */
  public List<ArtSpec> getVariantSpecs(String variant) throws IOException {
    List<ArtSpec> res = new ArrayList<ArtSpec>();
    switch (variant) {
    case "no_variant":
      // Not a variant test
      break;
    case "empty":
      // Empty repository
      break;
    case "commit1":
      // One committed artifact
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1).toCommit(true));
      break;
    case "uncommit1":
      // One uncommitted artifact
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1));
      break;
    case "url3":
      // Three committed versions
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1).toCommit(true));
      break;
    case "url3unc":
      // Mix of committed and uncommitted, two URLs
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1));
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1).toCommit(true));

      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL2).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL2).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL2));
      break;
    case "disjoint":
      // Different URLs in different collections and AUs
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1));
      res.add(ArtSpec.forCollAuUrl(COLL1, AUID1, URL1).toCommit(true));

      res.add(ArtSpec.forCollAuUrl(COLL2, AUID2, URL2).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL2, AUID2, URL2).toCommit(true));
      res.add(ArtSpec.forCollAuUrl(COLL2, AUID2, URL2));
      break;
    case "overlap":
      // Same URLs in different collections and AUs
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
      // Combinatorics of collection, AU, URL
      {
	boolean toCommit = false;
	for (String coll : COLLS) {
	  for (String auid : AUIDS) {
	    for (String url : URLS) {
	      res.add(ArtSpec.forCollAuUrl(coll, auid, url).toCommit(toCommit));
	      toCommit = !toCommit;
	    }
	  }
	}
      }
      break;
    case "grid3x3x3x3":
      // Combinatorics of collection, AU, URL w/ multiple versions
      {
	boolean toCommit = false;
	for (int ix = 1; ix <= 3; ix++) {
	  for (String coll : COLLS) {
	    for (String auid : AUIDS) {
	      for (String url : URLS) {
		res.add(ArtSpec.forCollAuUrl(coll, auid, url).toCommit(toCommit));
		toCommit = !toCommit;
	      }
	    }
	  }
	}
      }
      break;
    default:
      fail("getVariantSpecs called with unknown variant name: " + variant);
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
    log.debug("Running beforeEach()");
    setUpRepo();
    beforeVariant();
  }

  void setUpRepo() throws Exception {
    log.debug("Running setUpRepo()");
    this.repository = makeLockssRepository();
    this.repository.initRepository();
  }

  @AfterEach
  public void tearDownArtifactDataStore() throws Exception {
    log.debug("Running tearDownArtifactDataStore()");
    this.repository.shutdownRepository();
    this.repository = null;
  }

  // Set up the current variant: create appropriate ArtSpecs and add them
  // to the repository
  void beforeVariant() throws IOException {
    List<ArtSpec> scenario = getVariantSpecs(variant);
    instantiateScenario(scenario);
  }

  // Add Artifacts to the repository as specified by the ArtSpecs
  void instantiateScenario(List<ArtSpec> scenario) throws IOException {
    for (ArtSpec spec : scenario) {
      Artifact art = addUncommitted(spec);
      if (spec.isToCommit()) {
	commit(spec, art);
      }
    }      
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
  public void testArtifactSizes() throws IOException {
    for (int size = 0; size < MAX_INCR_FILE; size += 100) {
      testArtifactSize(size);
    }
  }

  public void testArtifactSize(int size) throws IOException {
    ArtSpec spec = ArtSpec.forCollAuUrl(COLL1, AUID1, URL1 + size)
      .toCommit(true).setContentLength(size);
    Artifact newArt = addUncommitted(spec);
    Artifact commArt = commit(spec, newArt);
    spec.assertData(repository, commArt);
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
    // up variants, and by testArtifactSizes(), but for the sake of
    // completeness ...

    ArtSpec spec = new ArtSpec().setUrl("https://mr/ed/").setContent(CONTENT1);
    Artifact newArt = addUncommitted(spec);
    Artifact commArt = commit(spec, newArt);
    spec.assertData(repository, commArt);
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

    // Ensure that a no-version retrieval gets the expected highest version
    for (ArtSpec highSpec : highestCommittedVerSpec.values()) {
      log.info("highSpec: " + highSpec);
      highSpec.assertData(repository, repository.getArtifact(
	  highSpec.getCollection(),
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
    // XXX should this throw?
    assertNull(repository.getArtifactData(COLL1, NO_ARTID));

    ArtSpec cspec = anyCommittedSpec();
    if (cspec != null) {
      ArtifactData ad = repository.getArtifactData(cspec.getCollection(),
						   cspec.getArtifactId());
      cspec.assertData(repository, ad);
      // should be in TestArtifactData
      assertThrowsMatch(IllegalStateException.class,
			"Can't call getInputStream\\(\\) more than once",
			() -> ad.getInputStream());
    }
    ArtSpec uspec = anyUncommittedSpec();
    if (uspec != null) {
      ArtifactData ad = repository.getArtifactData(uspec.getCollection(),
						   uspec.getArtifactId());
      uspec.assertData(repository, ad);
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
	spec.assertData(repository, getArtifact(repository, spec));
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

    // Calculate the expected size of each AU in each collection, compare
    // with auSize()
    for (String coll : addedCollections()) {
      for (String auid : addedAuids()) {
	long expSize = highestCommittedVerSpec.values().stream()
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
      commSpec.assertData(repository, dupArt);
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
		      "Non-existent artifact id: " + NO_ARTID,
		      () -> {repository.deleteArtifact(NO_COLL, NO_ARTID);});

    {
      // Delete a committed artifact that isn't the highest version. it
      // should disappear but size shouldn't change
      ArtSpec spec = committedSpecStream()
	.filter(s -> s != highestCommittedVerSpec.get(s.artButVerKey()))
	.findAny().orElse(null);
      if (spec != null) {
	long totsize = repository.auSize(spec.getCollection(), spec.getAuid());
	assertTrue(repository.artifactExists(spec.getCollection(),
					     spec.getArtifactId()));
	assertNotNull(getArtifact(repository, spec));
	log.info("Deleting not highest: " + spec);
	repository.deleteArtifact(spec.getCollection(), spec.getArtifactId());
	assertFalse(repository.artifactExists(spec.getCollection(),
					      spec.getArtifactId()));
	assertNull(getArtifact(repository, spec));
	delFromAll(spec);
	assertEquals(totsize,
		     (long)repository.auSize(spec.getCollection(),
					     spec.getAuid()),
		     "AU size changed after deleting non-highest version");
      }
    }
    {
      // Delete a highest-version committed artifact, it should disappear and
      // size should change
      ArtSpec spec = highestCommittedVerSpec.values().stream()
	.findAny().orElse(null);
      if (spec != null) {
	long totsize = repository.auSize(spec.getCollection(), spec.getAuid());
	long artsize = spec.getContentLength();
	assertTrue(repository.artifactExists(spec.getCollection(),
					     spec.getArtifactId()));
	assertNotNull(getArtifact(repository, spec));
	log.info("Deleting highest: " + spec);
	repository.deleteArtifact(spec.getCollection(), spec.getArtifactId());
	assertFalse(repository.artifactExists(spec.getCollection(),
					      spec.getArtifactId()));
	assertNull(getArtifact(repository, spec));
	delFromAll(spec);
	ArtSpec newHigh = highestCommittedVerSpec.get(spec.artButVerKey());
	long exp = totsize - artsize;
	if (newHigh != null) {
	  exp += newHigh.getContentLength();
	}
	assertEquals(exp,
		     (long)repository.auSize(spec.getCollection(),
					     spec.getAuid()),
		     variant + ": AU size wrong after deleting highest version");
	log.info(variant +
		 ": AU size right after deleting highest version was: "
		 + totsize + " now " + exp);
      }
    }
    // Delete an uncommitted artifact, it should disappear and size should
    // not change
    {
      ArtSpec uspec = anyUncommittedSpec();
      if (uspec != null) {
	long totsize =
	  repository.auSize(uspec.getCollection(), uspec.getAuid());
	assertTrue(repository.artifactExists(uspec.getCollection(),
					     uspec.getArtifactId()));
	assertNull(getArtifact(repository, uspec));
	log.info("Deleting uncommitted: " + uspec);
	repository.deleteArtifact(uspec.getCollection(), uspec.getArtifactId());
	assertFalse(repository.artifactExists(uspec.getCollection(),
					      uspec.getArtifactId()));
	assertNull(getArtifact(repository, uspec));
	delFromAll(uspec);
	assertEquals(totsize,
		     (long)repository.auSize(uspec.getCollection(),
					     uspec.getAuid()),
		     "AU size changed after deleting uncommitted");
      }
    }
    // TK Delete committed & uncommitted arts & check results each time
    // delete twice
    // check getAuIds() & getCollectionIds() as they run out

  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testGetArtifacts() throws IOException {
    // Illegal args
    assertThrowsMatch(IllegalArgumentException.class,
		      "Null collection id or au id",
		      () -> {repository.getArtifacts(null, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "au",
		      () -> {repository.getArtifacts(COLL1, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "collection",
		      () -> {repository.getArtifacts(null, AUID1);});

    // Non-existent collection & auid
    assertEmpty(repository.getArtifacts(NO_COLL, NO_AUID));

    String anyColl = null;
    String anyAuid = null;

    // Compare with all URLs in each AU
    for (String coll : addedCollections()) {
      anyColl = coll;
      for (String auid : addedAuids()) {
	anyAuid = auid;
	ArtSpec.assertArtList(repository, (orderedAllAu(coll, auid)
		       .filter(distinctByKey(ArtSpec::artButVerKey))),
		      repository.getArtifacts(coll, auid));
	
      }
    }

    // Combination of coll and au id that both exist, but have no artifacts
    // in common
    Pair<String,String> collau = collAuMismatch();
    if (collau != null) {
      assertEmpty(repository.getArtifacts(collau.getLeft(),
					  collau.getRight()));
    }
    // non-existent coll, au
    if (anyColl != null && anyAuid != null) {
      assertEmpty(repository.getArtifacts(anyColl,
					  anyAuid + "_notAuSuffix"));
      assertEmpty(repository.getArtifacts(anyColl + "_notCollSuffix",
					  anyAuid));
    }    
  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testGetArtifactsWithPrefix() throws IOException {
    // Illegal args
    assertThrowsMatch(IllegalArgumentException.class,
		      "Null collection id, au id or prefix",
		      () -> {repository.getArtifactsWithPrefix(null, null, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "prefix",
		      () -> {repository.getArtifactsWithPrefix(COLL1, AUID1, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "au",
		      () -> {repository.getArtifactsWithPrefix(COLL1, null, PREFIX1);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "collection",
		      () -> {repository.getArtifactsWithPrefix(null, AUID1, PREFIX1);});

    // Non-existent collection & auid
    assertEmpty(repository.getArtifactsWithPrefix(NO_COLL, NO_AUID, PREFIX1));
    // Compare with all URLs matching prefix in each AU
    for (String coll : addedCollections()) {
      for (String auid : addedAuids()) {
	ArtSpec.assertArtList(repository, (orderedAllAu(coll, auid)
		       .filter(spec -> spec.getUrl().startsWith(PREFIX1))
		       .filter(distinctByKey(ArtSpec::artButVerKey))),
		       repository.getArtifactsWithPrefix(coll, auid, PREFIX1));
	assertEmpty(repository.getArtifactsWithPrefix(coll, auid,
						      PREFIX1 + "notpath"));
      }
    }

    // Combination of coll and au id that both exist, but have no artifacts
    // in common
    Pair<String,String> collau = collAuMismatch();
    if (collau != null) {
      assertEmpty(repository.getArtifactsWithPrefix(collau.getLeft(),
						    collau.getRight(),
						    PREFIX1));
    }
  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testGetArtifactsAllVersions() throws IOException {
    // Illegal args
    assertThrowsMatch(IllegalArgumentException.class,
		      "Null collection id or au id",
		      () -> {repository.getArtifactsAllVersions(null, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "au",
		      () -> {repository.getArtifactsAllVersions(COLL1, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "collection",
		      () -> {repository.getArtifactsAllVersions(null, AUID1);});

    // Non-existent collection & auid
    assertEmpty(repository.getArtifactsAllVersions(NO_COLL, NO_AUID));

    String anyColl = null;
    String anyAuid = null;
    // Compare with all URLs all version in each AU
    for (String coll : addedCollections()) {
      anyColl = coll;
      for (String auid : addedAuids()) {
	anyAuid = auid;
	ArtSpec.assertArtList(repository, orderedAllAu(coll, auid),
		      repository.getArtifactsAllVersions(coll, auid));
	
      }
    }
    // Combination of coll and au id that both exist, but have no artifacts
    // in common
    Pair<String,String> collau = collAuMismatch();
    if (collau != null) {
      assertEmpty(repository.getArtifactsAllVersions(collau.getLeft(),
							collau.getRight()));
    }
    if (anyColl != null && anyAuid != null) {
      assertEmpty(repository.getArtifactsAllVersions(anyColl,
						     anyAuid + "_not"));
      assertEmpty(repository.getArtifactsAllVersions(anyColl + "_not",
						     anyAuid));
    }    
  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testGetArtifactsWithPrefixAllVersions() throws IOException {
    // Illegal args
    assertThrowsMatch(IllegalArgumentException.class,
		      "Null collection id, au id or prefix",
		      () -> {repository.getArtifactsWithPrefixAllVersions(null, null, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "prefix",
		      () -> {repository.getArtifactsWithPrefixAllVersions(COLL1, AUID1, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "au",
		      () -> {repository.getArtifactsWithPrefixAllVersions(COLL1, null, PREFIX1);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "collection",
		      () -> {repository.getArtifactsWithPrefixAllVersions(null, AUID1, PREFIX1);});

    // Non-existent collection & auid
    assertEmpty(repository.getArtifactsWithPrefixAllVersions(NO_COLL, NO_AUID, PREFIX1));
    // Compare with all URLs matching prefix in each AU
    for (String coll : addedCollections()) {
      for (String auid : addedAuids()) {
	ArtSpec.assertArtList(repository, (orderedAllAu(coll, auid)
		       .filter(spec -> spec.getUrl().startsWith(PREFIX1))),
		       repository.getArtifactsWithPrefixAllVersions(coll, auid, PREFIX1));
	assertEmpty(repository.getArtifactsWithPrefixAllVersions(coll, auid,
								 PREFIX1 + "notpath"));
      }
    }

    // Combination of coll and au id that both exist, but have no artifacts
    // in common
    Pair<String,String> collau = collAuMismatch();
    if (collau != null) {
      assertEmpty(repository.getArtifactsWithPrefixAllVersions(collau.getLeft(),
							       collau.getRight(),
							       PREFIX1));
    }
  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testGetArtifactsWithPrefixAllVersionsAllAus() throws IOException {
    // Illegal args
    assertThrowsMatch(IllegalArgumentException.class,
		      "Null collection id or prefix",
		      () -> {repository.getArtifactsWithPrefixAllVersionsAllAus(null, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "prefix",
		      () -> {repository.getArtifactsWithPrefixAllVersionsAllAus(COLL1, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "collection",
		      () -> {repository.getArtifactsWithPrefixAllVersionsAllAus(null, PREFIX1);});

    // Non-existent collection
    assertEmpty(repository.getArtifactsWithPrefixAllVersionsAllAus(NO_COLL, PREFIX1));
    // Compare with all URLs matching prefix
    for (String coll : addedCollections()) {
      ArtSpec.assertArtList(repository, (orderedAllCollAllAus(coll)
		  .filter(spec -> spec.getUrl().startsWith(PREFIX1))),
		  repository.getArtifactsWithPrefixAllVersionsAllAus(coll, PREFIX1));
      assertEmpty(repository.getArtifactsWithPrefixAllVersionsAllAus(coll,
								     PREFIX1 + "notpath"));
    }
  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testGetArtifactAllVersions() throws IOException {
    // Illegal args
    assertThrowsMatch(IllegalArgumentException.class,
		      "Null collection id, au id or url",
		      () -> {repository.getArtifactsAllVersions(null, null, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "url",
		      () -> {repository.getArtifactsAllVersions(COLL1, AUID1, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "au",
		      () -> {repository.getArtifactsAllVersions(COLL1, null, URL1);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "coll",
		      () -> {repository.getArtifactsAllVersions(null, AUID1, URL1);});

    // Non-existent collection, auid or url
    assertEmpty(repository.getArtifactsAllVersions(NO_COLL, AUID1, URL1));
    assertEmpty(repository.getArtifactsAllVersions(COLL1, NO_AUID, URL1));
    assertEmpty(repository.getArtifactsAllVersions(COLL1, AUID1, NO_URL));

    // For each ArtButVer in the repository, enumerate all its versions and
    // compare with expected
    Stream<ArtSpec> s =
      committedSpecStream().filter(distinctByKey(ArtSpec::artButVerKey));
    for (ArtSpec urlSpec : (Iterable<ArtSpec>)s::iterator) {
      ArtSpec.assertArtList(repository, orderedAllCommitted()
		    .filter(spec -> spec.sameArtButVer(urlSpec)),
		    repository.getArtifactsAllVersions(urlSpec.getCollection(),
						       urlSpec.getAuid(),
						       urlSpec.getUrl()));
    }
  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testGetArtifactAllVersionsAllAus() throws IOException {
    // Illegal args
    assertThrowsMatch(IllegalArgumentException.class,
		      "Null collection id or url",
		      () -> {repository.getArtifactsAllVersionsAllAus(null, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "url",
		      () -> {repository.getArtifactsAllVersionsAllAus(COLL1, null);});
    assertThrowsMatch(IllegalArgumentException.class,
		      "coll",
		      () -> {repository.getArtifactsAllVersionsAllAus(null, URL1);});

    // Non-existent collection or url
    assertEmpty(repository.getArtifactsAllVersionsAllAus(NO_COLL, URL1));
    assertEmpty(repository.getArtifactsAllVersionsAllAus(COLL1, NO_URL));

    // For each ArtButVer in the repository, enumerate all its versions and
    // compare with expected
    Stream<ArtSpec> s =
      committedSpecStream().filter(distinctByKey(ArtSpec::artButVerKey));
    for (ArtSpec urlSpec : (Iterable<ArtSpec>)s::iterator) {
      ArtSpec.assertArtList(repository, orderedAllCommittedAllAus()
		    .filter(spec -> spec.sameArtButVerAllAus(urlSpec)),
		    repository.getArtifactsAllVersionsAllAus(urlSpec.getCollection(),
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
    for (String coll : CollectionUtils.subtract(addedCollections(),
						addedCommittedCollections())) {
      assertEmpty(repository.getAuIds(coll));
    }
  }

  @VariantTest
  @EnumSource(StdVariants.class)
  public void testGetCollectionIds() throws IOException {
    Iterator<String> expColl =
      orderedAllCommitted()
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

  // UTILITIES


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

  List<String> addedCommittedUrls() {
    return addedSpecs.stream()
      .filter(spec -> spec.isCommitted())
      .map(ArtSpec::getUrl)
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

  Stream<ArtSpec> orderedAllCommitted() {
    return committedSpecStream()
      .sorted();
  }

  Stream<ArtSpec> orderedAllCommittedAllAus() {
    return committedSpecStream()
      .sorted(BY_DATE_BY_AUID_BY_DECREASING_VERSION);
  }

  public static <T> Predicate<T>
    distinctByKey(Function<? super T,Object> keyExtractor) {
    Set<Object> seen = new HashSet<>();
    return t -> seen.add(keyExtractor.apply(t));
  }

  Stream<ArtSpec> orderedAllColl(String coll) {
    return committedSpecStream()
      .filter(s -> s.getCollection().equals(coll))
      .sorted();
  }

  Stream<ArtSpec> orderedAllCollAllAus(String coll) {
    return committedSpecStream()
      .filter(s -> s.getCollection().equals(coll))
      .sorted(BY_URI_BY_DATE_BY_AUID_BY_DECREASING_VERSION);
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

  ArtSpec anyUncommittedSpecButVer() {
    return uncommittedSpecStream()
      .filter(spec -> !highestCommittedVerSpec.containsKey(spec.artButVerKey()))
      .findAny().orElse(null);
  }


  // Find a collection and an au that each have artifacts, but don't have
  // any artifacts in common
  Pair<String,String> collAuMismatch() {
    Set<Pair<String,String>> set = new HashSet<Pair<String,String>>();
    for (String coll : addedCommittedCollections()) {
      for (String auid : addedCommittedAuids()) {
	set.add(new ImmutablePair<String, String>(coll, auid));
      }
    }
    committedSpecStream()
      .forEach(spec -> {set.remove(
	  new ImmutablePair<String, String>(spec.getCollection(),
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
    
  // Return the highest version ArtSpec with same ArtButVer
  ArtSpec highestVer(ArtSpec likeSpec, Stream<ArtSpec> stream) {
    return stream
      .filter(spec -> spec.sameArtButVer(likeSpec))
      .max(Comparator.comparingInt(ArtSpec::getVersion))
      .orElse(null);
  }

  // Delete ArtSpec from record of what we've added to the repository,
  // adjust highest version maps accordingly
  protected void delFromAll(ArtSpec spec) {
    addedSpecs.remove(spec);
    String key = spec.artButVerKey();
    if (highestVerSpec.get(key) == spec) {
      ArtSpec newHigh = highestVer(spec, addedSpecStream());
      log.info("newHigh: " + newHigh);
      highestVerSpec.put(key, newHigh);
    }
    if (highestCommittedVerSpec.get(key) == spec) {
      ArtSpec newCommHigh = highestVer(spec, committedSpecStream());
      log.info("newCommHigh: " + newCommHigh);
      highestCommittedVerSpec.put(key, newCommHigh);
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
      spec.generateContent();
    }
    log.info("adding: " + spec);
    
    ArtifactData ad = spec.getArtifactData();
    Artifact newArt = repository.addArtifact(ad);
    assertNotNull(newArt);

    try {
      spec.assertData(repository, newArt);
    } catch (Exception e) {
      log.error("Caught exception adding uncommitted artifact: {}", e);
      log.error("spec = {}", spec);
      log.error("ad = {}", ad);
      log.error("newArt = {}", newArt);
      throw e;
    }
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
    if (expVers == 0) {
      // this test valid only when no other versions exist ArtSpec
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
    log.info("committing: " + art);
    Artifact commArt = null;
    try {
      commArt = repository.commitArtifact(spec.getCollection(), artId);
    } catch (Exception e) {
      log.error("Caught exception committing artifact: {}", e);
      log.error("spec = {}", spec);
      log.error("art = {}", art);
      log.error("artId = {}", artId);
      throw e;
    }
    assertNotNull(commArt);
    if (spec.getExpVer() > 0) {
      assertEquals(spec.getExpVer(), (int)commArt.getVersion());
    }
    spec.setCommitted(true);
    // Remember the highest version of this URL we've committed
    ArtSpec maxVerSpec = highestCommittedVerSpec.get(spec.artButVerKey());
    if (maxVerSpec == null || maxVerSpec.getVersion() < spec.getVersion()) {
      highestCommittedVerSpec.put(spec.artButVerKey(), spec);
    }
    assertTrue(repository.isArtifactCommitted(spec.getCollection(),
					      commArt.getId()));
    assertTrue(commArt.getCommitted());

    spec.assertData(repository, commArt);

    Artifact newArt = getArtifact(repository, spec);
    assertNotNull(newArt);
    assertTrue(repository.isArtifactCommitted(spec.getCollection(),
					      newArt.getId()));
    assertTrue(newArt.getCommitted());
    assertTrue(repository.artifactExists(spec.getCollection(), newArt.getId()));
    return newArt;
  }

  // These should all cause addArtifact to throw NPE 
  protected ArtifactData[] nullPointerArtData = {
    new ArtifactData(null, null, null),
    new ArtifactData(null, null, STATUS_LINE_OK), 
    new ArtifactData(null, stringInputStream(""), null),
    new ArtifactData(null, stringInputStream(""), STATUS_LINE_OK), 
    new ArtifactData(HEADERS1, null, null),
    new ArtifactData(HEADERS1, null, STATUS_LINE_OK), 
    new ArtifactData(HEADERS1, stringInputStream(""), null), 
  };    

  // These describe artifacts that getArtifact() should never find
  protected ArtSpec[] neverFoundArtSpecs = {
    ArtSpec.forCollAuUrl(NO_COLL, AUID1, URL1),
    ArtSpec.forCollAuUrl(COLL1, NO_AUID, URL1),
    ArtSpec.forCollAuUrl(COLL1, AUID1, NO_URL),
  };    

  /** Return list of ArtSpecs that shouldn't be found in the current
   * repository */
  protected List<ArtSpec> notFoundArtSpecs() {
    List<ArtSpec> res = new ArrayList<ArtSpec>();
    // Always include some that should never be found
    Collections.addAll(res, neverFoundArtSpecs);

    // Include an uncommitted artifact, if any
    ArtSpec uncSpec = anyUncommittedSpecButVer();
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
      diff_coll:
      for (ArtSpec auUrl : committedSpecStream()
	     .filter(distinctByKey(s -> s.getUrl() + "|" + s.getAuid()))
	     .collect(Collectors.toList())) {
	for (String coll : addedCommittedCollections()) {
	  ArtSpec a = auUrl.copy().setCollection(coll);
	  if (!highestCommittedVerSpec.containsKey(a.artButVerKey())) {
	    res.add(a);
	    break diff_coll;
	  }
	}
      }
      diff_au:
      for (ArtSpec auUrl : committedSpecStream()
	     .filter(distinctByKey(s -> s.getUrl() + "|" + s.getCollection()))
	     .collect(Collectors.toList())) {
	for (String auid : addedCommittedAuids()) {
	  ArtSpec a = auUrl.copy().setAuid(auid);
	  if (!highestCommittedVerSpec.containsKey(a.artButVerKey())) {
	    res.add(a);
	    break diff_au;
	  }
	}
      }
      diff_url:
      for (ArtSpec auUrl : committedSpecStream()
	     .filter(distinctByKey(s -> s.getAuid() + "|" + s.getCollection()))
	     .collect(Collectors.toList())) {
	for (String url : addedCommittedUrls()) {
	  ArtSpec a = auUrl.copy().setUrl(url);
	  if (!highestCommittedVerSpec.containsKey(a.artButVerKey())) {
	    res.add(a);
	    break diff_url;
	  }
	}
      }

      // and with correct coll, au, url but non-existent version
      res.add(commSpec.copy().setVersion(0));
      res.add(commSpec.copy().setVersion(1000));
    }

    return res;
  }

  InputStream stringInputStream(String str) {
    return IOUtils.toInputStream(str, Charset.defaultCharset());
  }


  // NOTE: this class is used by TestRestLockssRepository in the
  // laaws-repository-service project

  /** All the info needed to create and store an Artifact, or to compare
   * with a retrieved Artifact */
  public static class ArtSpec implements Comparable<Object> {
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
    String contentDigest;
    String storageUrl;
    long originDate = -1;

    // state
    boolean isCommitted = false;
    String artId;
    
    public ArtSpec copy() {
      return ArtSpec.forCollAuUrl(coll, auid, url)
	.setStatusLine(getStatusLine())
	.setHeaders(new HashMap<String,String>(getHeaders()))
	.setContent(getContent())
	.setContentLength(len)
	.setOriginDate(originDate);
    }

    public static ArtSpec forCollAuUrl(String coll, String auid, String url) {
      return new ArtSpec()
	.setCollection(coll)
	.setAuid(auid)
	.setUrl(url);
    }

    public static ArtSpec forCollAuUrlVer(String coll, String auid,
				   String url, int version) {
      return ArtSpec.forCollAuUrl(coll, auid, url).setVersion(version);
    }

    public ArtSpec setUrl(String url) {
      this.url = url;
      return this;
    }
    
    public ArtSpec setExpVer(int ver) {
      this.expVer = ver;
      return this;
    }
    
    public ArtSpec setCollection(String coll) {
      this.coll = coll;
      return this;
    }
    
    public ArtSpec setAuid(String auid) {
      this.auid = auid;
      return this;
    }
    
    public ArtSpec setContent(String content) {
      this.content = content;
      return this;
    }
    
    public ArtSpec setVersion(int version) {
      this.fixedVer = version;
      return this;
    }
    
    public ArtSpec setArtifactId(String id) {
      this.artId = id;
      return this;
    }
    
    public ArtSpec setContentLength(long len) {
      this.len = len;
      return this;
    }

    public ArtSpec setContentDigest(String contentDigest) {
      this.contentDigest = contentDigest;
      return this;
    }

    public ArtSpec setStorageUrl(String storageUrl) {
      this.storageUrl = storageUrl;
      return this;
    }

    public ArtSpec setHeaders(Map<String,String> headers) {
      this.headers = headers;
      return this;
    }

    public ArtSpec setStatusLine(StatusLine statLine) {
      this.statLine = statLine;
      return this;
    }

    public ArtSpec setOriginDate(long originDate) {
      this.originDate = originDate;
      return this;
    }

    public ArtSpec toCommit(boolean toCommit) {
      this.toCommit = toCommit;
      return this;
    }

    public boolean isToCommit() {
      return toCommit;
    }

    public ArtSpec setCommitted(boolean committed) {
      this.isCommitted = committed;
      return this;
    }

    public boolean isCommitted() {
      return isCommitted;
    }

    public String getUrl() {
      return url;
    }
    
    public String getCollection() {
      return coll;
    }
    
    public String getAuid() {
      return auid;
    }
    
    public int getVersion() {
      return fixedVer;
    }
    
    public boolean hasVersion() {
      return fixedVer >= 0;
    }
    
    public int getExpVer() {
      return expVer;
    }
    
    public String getArtifactId() {
      return artId;
    }
    
    public boolean hasContent() {
      return content != null || iStream != null;
    }

    public ArtSpec generateContent() {
      if (len >= 0) {
	if (len > Integer.MAX_VALUE) {
	  throw new IllegalArgumentException("Refusing to generate content > 2GB: "
					     + len);
	}
	setContent(RandomStringUtils.randomAlphabetic((int)len));
      } else {
	setContent(RandomStringUtils.randomAlphabetic(0, MAX_RANDOM_FILE));
      }
      log.info("gen content");
      return this;
    }

    public String getContent() {
      return content;
    }
    
    public long getContentLength() {
      if (len >= 0) {
	return len;
      } else if (content != null) {
	return content.length();
      } else {
	throw new IllegalStateException("getContentLen() called when length unknown");
      }
    }
    
    public String getContentDigest() {
      log.trace("content = " + content);
      // Check whether content has been defined.
      if (content != null) {
	// Yes: Check whether the content digest needs to be computed.
	if (contentDigest == null) {
	  // Yes: Compute it.
	  String algorithmName = ArtifactData.DEFAULT_DIGEST_ALGORITHM;

	  try {
	    MessageDigest digest = MessageDigest.getInstance(algorithmName);
	    contentDigest = String.format("%s:%s", digest.getAlgorithm(),
		new String(Hex.encodeHex(
		    digest.digest(content.getBytes(StandardCharsets.UTF_8)))));
	  } catch (NoSuchAlgorithmException nsae) {
	    String errMsg = String.format("Unknown digest algorithm: %s; "
		+ "could not instantiate a MessageDigest", algorithmName);
	    log.error(errMsg);
	    throw new RuntimeException(errMsg);
	  }
	}

	log.trace("contentDigest = " + contentDigest);
	return contentDigest;
      } else {
	// No: Report the problem.
	throw new IllegalStateException(
	    "getContentDigest() called when content unknown");
      }
    }
    
    public String getStorageUrl() {
      return storageUrl;
    }

    public Map<String,String> getHeaders() {
      return headers;
    }

    public String getHeadersAsText() {
      StringBuilder sb = new StringBuilder();

      for (Map.Entry<String, String> entry : headers.entrySet()) {
	sb.append(entry.getKey()).append(": ").append(entry.getValue())
	.append("\n");
      }

      return sb.toString();
    }

    public StatusLine getStatusLine() {
      return statLine;
    }

    public long getOriginDate() {
      if (originDate >= 0) {
	return originDate;
      } else if (getArtifactData() != null) {
	return getArtifactData().getOriginDate();
      } else {
	throw new IllegalStateException("getOriginDate() called when origin date unknown");
      }
    }

    public HttpHeaders getMetdata() {
      return RepoUtil.httpHeadersFromMap(headers);
    }

    public ArtifactIdentifier getArtifactIdentifier() {
      return new ArtifactIdentifier(coll, auid, url, -1);
    }

    public ArtifactData getArtifactData() {
      return new ArtifactData(getArtifactIdentifier(), getMetdata(),
			      getInputStream(), getStatusLine());
    }

    public InputStream getInputStream() {
      if (content != null) {
	return IOUtils.toInputStream(content, Charset.defaultCharset());
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

    /** Return a key that's unique to the collection,url */
    public String artButVerKeyAllAus() {
      return getCollection() + "|" + getUrl();
    }

    /** true if other refers to an artifact with the same collection
     * and url, independent of AU and version. */
    public boolean sameArtButVerAllAus(ArtSpec other) {
      return artButVerKeyAllAus().equals(other.artButVerKeyAllAus());
    }

    /** Assert that the Artifact matches this ArtSpec */
    public void assertData(LockssRepository repository, Artifact art)
	throws IOException {
      try {
	Assertions.assertNotNull(art, "Comparing with " + this);
	Assertions.assertEquals(getCollection(), art.getCollection());
	Assertions.assertEquals(getAuid(), art.getAuid());
	Assertions.assertEquals(getUrl(), art.getUri());
	if (getExpVer() >= 0) {
	  Assertions.assertEquals(getExpVer(), (int)art.getVersion());
	}
	Assertions.assertEquals(getContentLength(), art.getContentLength());
	Assertions.assertEquals(getContentDigest(), art.getContentDigest());

	if (getStorageUrl() != null) {
	  Assertions.assertEquals(getStorageUrl(), art.getStorageUrl());
	}

	Assertions.assertEquals(getOriginDate(), art.getOriginDate());

	ArtifactData ad = repository.getArtifactData(art);
	Assertions.assertEquals(art.getIdentifier(), ad.getIdentifier());
	Assertions.assertEquals(getContentLength(), ad.getContentLength());
	Assertions.assertEquals(getContentDigest(), ad.getContentDigest());
	assertData(repository, ad);

	ArtifactData ad2 = repository.getArtifactData(getCollection(),
  						  art.getId());
	Assertions.assertEquals(getContentLength(), ad2.getContentLength());
	Assertions.assertEquals(getContentDigest(), ad2.getContentDigest());
	assertData(repository, ad2);
      } catch (Exception e) {
	log.error("Caught exception asserting artifact: {}", e);
	log.error("art = {}", art);
	log.error("spec = {}", this);
	throw e;
      }
    }

    public void assertEquals(StatusLine exp, StatusLine line) {
      Assertions.assertEquals(exp.toString(), line.toString());
    }

    /** Assert that the ArtifactData matches the ArtSpec */
    public void assertData(LockssRepository repository, ArtifactData ad)
	throws IOException {
      Assertions.assertNotNull(ad, "Didn't find ArticleData for: " + this);
      assertEquals(getStatusLine(), ad.getHttpStatus());
      Assertions.assertEquals(getContentLength(), ad.getContentLength());
      Assertions.assertEquals(getContentDigest(), ad.getContentDigest());

      if (getStorageUrl() != null) {
	Assertions.assertEquals(getStorageUrl(), ad.getStorageUrl());
      }

      new LockssTestCase5().assertSameBytes(getInputStream(),
	  ad.getInputStream(), getContentLength());
      Assertions.assertEquals(getHeaders(),
  		 RepoUtil.mapFromHttpHeaders(ad.getMetadata()));
    }

    /** Assert that the sequence of Artifacts matches the stream of ArtSpecs */
    public static void assertArtList(LockssRepository repository,
	Stream<ArtSpec> expSpecs, Iterable<Artifact> arts) throws IOException {
      Iterator<ArtSpec> specIter = expSpecs.iterator();
      Iterator<Artifact> artIter = arts.iterator();
      while (specIter.hasNext() && artIter.hasNext()) {
        ArtSpec spec = specIter.next();
        Artifact art = artIter.next();
        spec.assertData(repository, art);
      }
      Assertions.assertFalse(specIter.hasNext());
      Assertions.assertFalse(artIter.hasNext());
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
