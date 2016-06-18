package it.unitn.disi.diversicon;

import static it.unitn.disi.diversicon.internal.Internals.checkArgument;
import static it.unitn.disi.diversicon.internal.Internals.checkH2;
import static it.unitn.disi.diversicon.internal.Internals.checkNotBlank;
import static it.unitn.disi.diversicon.internal.Internals.checkNotEmpty;
import static it.unitn.disi.diversicon.internal.Internals.checkNotNull;

import java.io.File;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import org.h2.tools.Script;
import org.hibernate.CacheMode;
import org.hibernate.Criteria;
import org.hibernate.Query;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.hibernate.Transaction;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.hibernate.service.ServiceRegistryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tudarmstadt.ukp.lmf.api.Uby;
import de.tudarmstadt.ukp.lmf.model.core.LexicalResource;
import de.tudarmstadt.ukp.lmf.model.enums.ERelNameSemantics;
import de.tudarmstadt.ukp.lmf.model.morphology.Lemma;
import de.tudarmstadt.ukp.lmf.model.semantics.Synset;
import de.tudarmstadt.ukp.lmf.model.semantics.SynsetRelation;
import de.tudarmstadt.ukp.lmf.transform.DBConfig;
import de.tudarmstadt.ukp.lmf.transform.XMLToDBTransformer;
import it.unitn.disi.diversicon.internal.Internals;

/**
 * Extension of {@link de.tudarmstadt.ukp.lmf.api.Uby Uby} LMF knowledge base
 * with some
 * additional fields in the db to speed up computations. In particular, original
 * {@link SynsetRelation}
 * is extended by {@link DivSynsetRelation} by adding {@code depth} and
 * {@code provenance} edges.
 * 
 * To create instances use {@link #connectToDb(DBConfig)} method
 *
 * @since 0.1
 */
public class Diversicon extends Uby {

    /**
     * The version of the currently supported schema.
     * 
     * @since 0.1.0
     */
    public static final String DIVERSICON_SCHEMA_VERSION = "1.0";

    private static final Logger LOG = LoggerFactory.getLogger(Diversicon.class);

    /**
     * Maps a Diversicon Session hashcode to its Diversicon
     */
    private static final Map<Integer, Diversicon> INSTANCES = new HashMap();

    /**
     * Amount of items to flush when writing into db with Hibernate.
     */
    private static final int BATCH_FLUSH_COUNT = 20;

    /**
     * @since 0.1
     */
    public static final String DEFAULT_AUTHOR = "Default author";

    /**
     * The url protocol for lexical resources loaded from memory.
     */
    public static final String MEMORY_PROTOCOL = "memory";

    private ImportLogger importLogger;

    /**
     * @throws DivIoException
     * @throws InvalidSchemaException 
     * 
     * @since 0.1
     */
    protected Diversicon(DBConfig dbConfig) {
        super(); // so it doesn't open connections! Let's hope they don't delete
                 // it!

        checkNotNull(dbConfig, "database configuration is null");

        this.dbConfig = dbConfig;

        if (Diversicons.isSchemaValid(dbConfig)) {
            LOG.info("Reusing existing database at " + dbConfig.getJdbc_url());
            cfg = Diversicons.getHibernateConfig(dbConfig, true);
        } else {
            throw new InvalidSchemaException("Database schema is not valid! DbConfig is " + Diversicons.toString(dbConfig, false));
        }

        ServiceRegistryBuilder serviceRegistryBuilder = new ServiceRegistryBuilder().applySettings(cfg.getProperties());
        sessionFactory = cfg.buildSessionFactory(serviceRegistryBuilder.buildServiceRegistry());
        session = sessionFactory.openSession();
        
        int hashcode = session.hashCode();
        if (INSTANCES.containsKey(hashcode)) {
            throw new DivException("INTERNAL ERROR: Seems like there is some sort of duplicate Diversicon session!!");
        }
        INSTANCES.put(hashcode, this);

    }

    /**
     * Note: search is done by exact match on {@code wirttenForm}
     * 
     * @since 0.1
     */
    // todo review, not so clear how lemmas are supposed to work
    public List<Lemma> getLemmasByWrittenForm(String writtenForm) {
        checkNotEmpty(writtenForm, "Invalid written form!");

        // searching only in lemmas because UBY wordnet only generates lemmas
        // FormRepresentations
        // and doesn't create any WordForm (see
        // https://github.com/dkpro/dkpro-uby/blob/5cab2846e3c27069c08ebd3bf91bd5a6f8ed02ca/de.tudarmstadt.ukp.uby.integration.wordnet-gpl/src/main/java/de/tudarmstadt/ukp/lmf/transform/wordnet/LexicalEntryGenerator.java#L271)

        @SuppressWarnings("unchecked")
        List<Lemma> lemmas = session.createCriteria(Lemma.class)
                                    .createCriteria("formRepresentations")
                                    .add(Restrictions.like("writtenForm", writtenForm))
                                    .list();

        return lemmas;
    }

    /**
     * See {@link #getLemmasByWrittenForm(String)}.
     * 
     * @since 0.1
     */
    public List<String> getLemmaStringsByWrittenForm(String writtenForm) {
        List<Lemma> lemmas = getLemmasByWrittenForm(writtenForm);

        List<String> ret = new ArrayList();
        for (Lemma lemma : lemmas) {
            if (lemma.getFormRepresentations()
                     .isEmpty()) {
                LOG.error(
                        "Found a lemma with no form representation! Lemma's lexical entry is " + lemma.getLexicalEntry()
                                                                                                      .getId());
            } else {
                ret.add(lemma.getFormRepresentations()
                             .get(0)
                             .getWrittenForm());
            }
        }
        return ret;
    }

    /**
     * Finds all of the synsets to which {@code synsetId} is connected with
     * edges of
     * {@code relNames}
     * having depth less or equal the given one.
     * 
     * @param relNames
     *            if none is provided an empty set iterator is returned.
     * @param depth
     *            the maximum depth edges can have. If -1 no depth limit is
     *            applied.
     *            If zero an empty set iterator is returned.
     * 
     * @since 0.1
     */
    public Iterator<Synset> getConnectedSynsets(
            String synsetId,
            int depth,
            Iterable<String> relNames) {

        checkNotEmpty(synsetId, "Invalid synset id!");
        checkNotNull(relNames, "Invalid relation names!");
        checkArgument(depth >= -1, "Depth must be >= -1 , found instead: " + depth);

        if (!relNames.iterator()
                     .hasNext()
                || depth == 0) {
            return new ArrayList().iterator();
        }

        List<String> directRelations = new ArrayList();
        List<String> inverseRelations = new ArrayList();

        for (String relName : relNames) {
            if (Diversicons.isCanonicalRelation(relName) || !Diversicons.hasInverse(relName)) {
                directRelations.add(relName);
            } else {
                inverseRelations.add(Diversicons.getInverse(relName));
            }
        }

        String directDepthConstraint;
        String inverseDepthConstraint;
        if (depth == -1) {
            directDepthConstraint = "";
            inverseDepthConstraint = "";
        } else {
            directDepthConstraint = " AND SRD.depth <= " + depth;
            inverseDepthConstraint = " AND SRR.depth <= " + depth;
        }

        String directHsql;
        if (directRelations.isEmpty()) {
            directHsql = "";
        } else { // UNION doesn't work in hibernate
            directHsql = "  S.id IN "
                    + "             ("
                    + "                 SELECT SRD.target.id"
                    + "                 FROM   SynsetRelation SRD"
                    + "                 WHERE  SRD.source.id = :synsetId"
                    + "                 AND SRD.relName IN " + makeSqlList(directRelations)
                    + directDepthConstraint
                    + "             )";
        }

        String inverseHsql;
        if (inverseRelations.isEmpty()) {
            inverseHsql = "";
        } else {
            inverseHsql = " S.id IN"
                    + "             ("
                    + "                 SELECT SRR.source.id"
                    + "                 FROM   SynsetRelation SRR"
                    + "                 WHERE        SRR.target.id = :synsetId"
                    + "                          AND SRR.relName IN " + makeSqlList(inverseRelations)
                    + inverseDepthConstraint
                    + "              )";

        }

        String orHsql;
        if (!directRelations.isEmpty() && !inverseRelations.isEmpty()) {
            orHsql = " OR ";
        } else {
            orHsql = "";
        }

        String queryString = " "
                + "             FROM Synset S"
                + "             WHERE "
                + directHsql
                + orHsql
                + inverseHsql;

        Query query = session.createQuery(queryString);
        query
             .setParameter("synsetId", synsetId);

        return query.iterate();
    }

    /**
     * See {{@link #getConnectedSynsets(String, int, Iterable)}}
     * 
     * @since 0.1
     */
    public Iterator<Synset> getConnectedSynsets(
            String synsetId,
            int depth,
            String... relNames) {
        return getConnectedSynsets(synsetId, depth, Arrays.asList(relNames));
    }

    /**
     * Normalizes and augments the synsetRelation graph with edges to speed up
     * searches.
     * 
     * @since 0.1
     */
    // todo what about provenance? todo instances?
    public void processGraph() {

        normalizeGraph();

        computeTransitiveClosure();

    }

    /**
     * Returns the {@link DbInfo}
     * 
     * @throws DivNotFoundException
     *             if dbInfo is not found
     * @since 0.1
     */
    public DbInfo getDbInfo() {

        Criteria crit = session.createCriteria(DbInfo.class);
        crit.setMaxResults(50);
        DbInfo ret = (DbInfo) crit.uniqueResult();

        if (ret == null) {
            throw new DivNotFoundException("Couldn't find DbInfo in the database!!");
        } else {
            return ret;
        }
    }

    /**
     * Returns {@code true} if {@code source} contains a relation toward
     * {@code target} synset.
     * Returns false otherwise.
     */
    private static boolean containsRel(Synset source, Synset target, String relName) {
        checkNotNull(source, "Invalid source!");
        checkNotNull(target, "Invalid target!");
        checkNotEmpty(relName, "Invalid relName!");

        for (SynsetRelation synRel : source.getSynsetRelations()) {
            if (relName.equals(synRel.getRelName())
                    && Objects.equals(synRel.getTarget()
                                            .getId(),
                            (target.getId()))) {
                return true;
            }
        }
        return false;
    }

    private static String quote(String s) {
        return "'" + s + "'";
    }

    private static String makeSqlList(String[] iterable) {
        return makeSqlList(Arrays.asList(iterable));
    }

    protected static String makeSqlList(Iterable<String> iterable) {
        StringBuilder retb = new StringBuilder("(");

        boolean first = true;
        for (String s : iterable) {
            if (first) {
                retb.append(quote(s));
                first = false;
            } else {
                retb.append(", " + quote(s));
            }
        }
        retb.append(")");
        return retb.toString();
    }

    /*
     * Adds missing edges of depth 1 for relations we consider as canonical.
     * 
     * @throws DivException
     */
    private void normalizeGraph() {
        LOG.warn("TODO: SHOULD CHECK FOR LOOPS!");

        LOG.info("Normalizing SynsetRelations...");

        Transaction tx = null;
        try {
            tx = session.beginTransaction();

            String hql = "FROM Synset";
            Query query = session.createQuery(hql);

            ScrollableResults synsets = query
                                             .setCacheMode(CacheMode.IGNORE)
                                             .scroll(ScrollMode.FORWARD_ONLY);
            int count = 0;

            InsertionStats relStats = new InsertionStats();

            while (synsets.next()) {

                Synset synset = (Synset) synsets.get(0);
                LOG.info("Processing synset with id " + synset.getId() + " ...");

                List<SynsetRelation> relations = synset.getSynsetRelations();

                for (SynsetRelation sr : relations) {
                    DivSynsetRelation ssr = (DivSynsetRelation) sr;

                    if (Diversicons.hasInverse(ssr.getRelName())) {
                        String inverseRelName = Diversicons.getInverse(ssr.getRelName());
                        if (Diversicons.isCanonicalRelation(inverseRelName)
                                && Diversicons.isTransitive(inverseRelName)
                                && !containsRel(ssr.getTarget(),
                                        ssr.getSource(),
                                        inverseRelName)) {
                            DivSynsetRelation newSsr = new DivSynsetRelation();

                            newSsr.setDepth(1);
                            newSsr.setProvenance(Diversicon.getProvenanceId());
                            newSsr.setRelName(inverseRelName);
                            newSsr.setRelType(Diversicons.getRelationType(inverseRelName));
                            newSsr.setSource(ssr.getTarget());
                            newSsr.setTarget(ssr.getSource());

                            ssr.getTarget()
                               .getSynsetRelations()
                               .add(newSsr);
                            session.save(newSsr);
                            session.saveOrUpdate(ssr.getTarget());
                            relStats.inc(inverseRelName);
                        }
                    }

                }

                if (++count % BATCH_FLUSH_COUNT == 0) {
                    // flush a batch of updates and release memory:
                    session.flush();
                    session.clear();
                }
            }

            DbInfo dbInfo = getDbInfo();
            dbInfo.setToNormalize(false);
            session.saveOrUpdate(dbInfo);

            tx.commit();

            LOG.info("");
            LOG.info("Done normalizing SynsetRelations.");
            LOG.info("");
            LOG.info(relStats.toString());

        } catch (Exception ex) {
            LOG.error("Error while normalizing graph! Rolling back!");
            if (tx != null) {
                tx.rollback();
            }
            throw new DivException("Error while computing normalizing graph!", ex);
        }
    }

    /**
     * Computes the transitive closure of
     * {@link Diversicons#getCanonicalTransitiveRelations() canonical relations}
     * 
     * Before calling this, the graph has to be normalized by calling
     * {@link #normalizeGraph()}
     * 
     * @throws DivException
     *             when transaction goes wrong it is automatically rolled back
     *             and DivException is thrown
     */
    private void computeTransitiveClosure() {

        LOG.info("Computing transitive closure for SynsetRelations ...");

        Transaction tx = null;
        try {
            tx = session.beginTransaction();

            InsertionStats relStats = new InsertionStats();

            int depthToSearch = 1;
            int count = 0;

            // As: the edges computed so far
            // Bs: original edges

            String hqlSelect = "    SELECT SR_A.source, SR_B.target,  SR_A.relName"
                    + "      FROM SynsetRelation SR_A, SynsetRelation SR_B"
                    + "      WHERE"
                    + "          SR_A.relName IN " + makeSqlList(Diversicons.getRelations())
                    + "      AND SR_A.depth = :depth"
                    + "      AND SR_B.depth = 1"
                    + "      AND SR_A.relName = SR_B.relName"
                    + "      AND SR_A.target = SR_B.source"
                    // don't want to add twice edges
                    + "      AND NOT EXISTS"
                    + "             ("
                    + "               "
                    + "               FROM SynsetRelation SR_C"
                    + "               WHERE "
                    + "                         SR_A.relName = SR_C.relName"
                    + "                    AND  SR_A.source=SR_C.source"
                    + "                    AND  SR_B.target=SR_C.target"
                    + "             )";

            int processedRelationsInCurLevel = 0;

            do {
                processedRelationsInCurLevel = 0;

                // log.info("Augmenting SynsetRelation graph with edges of depth
                // " +
                // (depthToSearch + 1) + " ...");

                Query query = session.createQuery(hqlSelect);
                query.setParameter("depth", depthToSearch);

                ScrollableResults results = query
                                                 .setCacheMode(CacheMode.IGNORE)
                                                 .scroll(ScrollMode.FORWARD_ONLY);
                while (results.next()) {

                    Synset source = (Synset) results.get(0);
                    Synset target = (Synset) results.get(1);
                    String relName = (String) results.get(2);

                    DivSynsetRelation ssr = new DivSynsetRelation();
                    ssr.setDepth(depthToSearch + 1);
                    ssr.setProvenance(Diversicon.getProvenanceId());
                    ssr.setRelName(relName);
                    ssr.setRelType(Diversicons.getRelationType(relName));
                    ssr.setSource(source);
                    ssr.setTarget(target);

                    source.getSynsetRelations()
                          .add(ssr);
                    session.save(ssr);
                    session.saveOrUpdate(source);
                    // log.info("Inserted " + ssr.toString());
                    relStats.inc(relName);
                    processedRelationsInCurLevel += 1;

                    if (++count % BATCH_FLUSH_COUNT == 0) {
                        // flush a batch of updates and release memory:
                        session.flush();
                        session.clear();
                    }
                }

                depthToSearch += 1;

            } while (processedRelationsInCurLevel > 0);

            DbInfo dbInfo = getDbInfo();
            dbInfo.setToAugment(false);
            session.saveOrUpdate(dbInfo);

            tx.commit();

            LOG.info("");
            LOG.info("Done computing transitive closure for SynsetRelations:");
            LOG.info("");
            LOG.info(relStats.toString());
        } catch (Exception ex) {
            LOG.error("Error while computing transitive closure! Rolling back!");
            if (tx != null) {
                tx.rollback();
            }
            throw new DivException("Error while computing transitive closure!", ex);
        }
    }

    /**
     * 
     * See {@link #importFiles(ImportConfig)}
     * 
     * 
     * @since 0.1
     */
    public ImportJob importFile(String filepath) {

        ImportConfig config = new ImportConfig();

        config.setAuthor(DEFAULT_AUTHOR);
        config.setFileUrls(Arrays.asList(filepath));

        return importFiles(config).get(0);
    }

    /**
     * Imports files, and each file import is going to be a separate
     * transaction. In case one
     * fails... TODO! Call is synchronous, after finishing returns logs of each
     * import.
     * 
     * @since 0.1
     */
    public List<ImportJob> importFiles(ImportConfig config) {

        List<ImportJob> ret = new ArrayList();

        checkNotNull(config);

        checkNotEmpty(config.getAuthor(), "Invalid ImportConfig author!");
        checkNotEmpty(config.getFileUrls(), "Invalid ImportConfig filepaths!");

        int i = 0;
        for (String fileUrl : config.getFileUrls()) {
            checkNotEmpty(fileUrl, "Invalid file url at position " + i + "!");
            i++;
        }

        LOG.info("Going to import " + config.getFileUrls()
                                            .size()
                + " files by import author " + config.getAuthor() + "...");

        Transaction tx = null;
        try {

            tx = session.beginTransaction();

            DbInfo dbInfo = getDbInfo();
            dbInfo.setToNormalize(true);
            dbInfo.setToAugment(true);
            session.saveOrUpdate(dbInfo);

            tx.commit();
        } catch (Exception ex) {
            LOG.error("Error while setting normalize/augment flags in db, rolling back!");
            if (tx != null) {
                tx.rollback();
            }

            throw new DivException("Error while setting normalize flag in db", ex);
        }

        for (String filepath : config.getFileUrls()) {

            LOG.info("Loading LMF : " + filepath + " ...");

            String lexicalResourceName = Diversicons.extractNameFromLexicalResource(new File(filepath));

            ImportJob job = startImportJob(config, filepath, lexicalResourceName);

            File file = Internals.readData(filepath).toFile();
            
            XMLToDBTransformer trans = new XMLToDBTransformer(dbConfig);

            try {
                trans.transform(file, null);
            } catch (Exception ex) {
                throw new DivException("Error while loading lmf xml " + filepath, ex);
            }

            LOG.info("Done loading LMF : " + filepath + " .");

            endImportJob(job);

            ret.add(job);
        }

        try {
            if (config.isSkipAugment()) {
                LOG.info("Skipping graph augmentation as requested by user.");
            } else {
                processGraph();

            }
        } catch (Exception ex) {
            throw new DivException("Error while augmenting graph with computed edges!", ex);
        }

        LOG.info("Done importing " + config.getFileUrls()
                                           .size()
                + " LMFs by import author " + config.getAuthor() + ".");

        return ret;
    }

    /**
     * @since 0.1.0
     */
    private void endImportJob(ImportJob job) {
        checkNotNull(job);

        Transaction tx = null;
        try {
            tx = session.beginTransaction();
            job.setEndDate(new Date());
            session.saveOrUpdate(job);

            DbInfo dbInfo = getDbInfo();
            dbInfo.setCurrentImportJob(null);
            session.saveOrUpdate(dbInfo);

            tx.commit();

        } catch (Exception ex) {
            LOG.error("Error while ending import job in db, rolling back!");
            if (tx != null) {
                tx.rollback();
            }

            throw new DivException("Error while ending import job in db!", ex);
        }

    }

    /**
     * !!!! IMPORTANT !!!!! You are supposed to know in advance the
     * {@code lexicalResourceName },
     * which must match the {@code name} of the lexical resource inside the
     * file!!
     * 
     * @since 0.1.0
     */
    private ImportJob startImportJob(
            ImportConfig config,
            String filepath,
            String lexicalResourceName) {

        checkNotNull(config);
        checkNotEmpty(filepath, "Invalid filepath!");
        checkNotEmpty(lexicalResourceName, "Invalid lexical resource name!");

        Transaction tx = null;
        try {
            tx = session.beginTransaction();

            ImportJob job = new ImportJob();

            job.setAuthor(config.getAuthor());
            job.setDescription(config.getDescription());
            job.setStartDate(new Date());
            job.setFileUrl(filepath);
            job.setLexicalResourceName(lexicalResourceName);

            session.saveOrUpdate(job);

            DbInfo dbInfo = getDbInfo();
            dbInfo.setCurrentImportJob(job);
            session.saveOrUpdate(dbInfo);

            tx.commit();
            return job;

        } catch (Exception ex) {
            LOG.error("Error while adding import job in db, rolling back!");
            if (tx != null) {
                tx.rollback();
            }

            throw new DivException("Error while adding import job in db!", ex);
        }
    }

    /**
     * 
     * Saves a LexicalResource complete with all the lexicons, synsets, etc into
     * a database. This method is suitable only for small lexical resources,
     * generally for testing purposes. If you have a big resource, stream the
     * loading by providing your implementation of <a href=
     * "https://github.com/dkpro/dkpro-uby/blob/master/de.tudarmstadt.ukp.uby.persistence.transform-asl/src/main/java/de/tudarmstadt/ukp/lmf/transform/LMFDBTransformer.java"
     * target="_blank"> LMFDBTransformer</a> and then call {@code transform()}
     * on it
     * instead. Call is synchronous, after finishing returns a log of the
     * import.
     * 
     * @param lexicalResourceId
     *            todo don't know well the meaning
     * @param skipAugment
     *            if true after the import the graph is nomralized
     *            and augmented with transitive losure.
     * @throws DivException
     * @since 0.1
     */
    public ImportJob importResource(
            LexicalResource lexicalResource,
            boolean skipAugment) {

        checkNotNull(lexicalResource);

        LOG.info("Going to save lexical resource to database...");

        ImportJob job = null;

        try {
            ImportConfig config = new ImportConfig();
            config.setSkipAugment(skipAugment);
            config.setAuthor(DEFAULT_AUTHOR);

            String fileUrl = MEMORY_PROTOCOL + ":" + lexicalResource.hashCode();

            config.addLexicalResource(fileUrl);

            job = startImportJob(config, fileUrl, lexicalResource.getName());

            new JavaToDbTransformer(dbConfig, lexicalResource).transform();

            endImportJob(job);

        } catch (Exception ex) {
            throw new DivException("Error when importing lexical resource "
                    + lexicalResource.getName() + " !", ex);
        }
        LOG.info("Done saving.");

        if (skipAugment) {
            processGraph();
        }

        return job;
    }

    /**
     * Returns the fully qualified package name.
     * 
     * @since 0.1
     */
    public static String getProvenanceId() {
        return Diversicon.class.getPackage()
                               .getName();
    }

    /**
     * Creates an instance of a Diversicon and opens a connection to the db.
     * Db must already exists. Present schema will be validated against required one
     * and if it doesn't match InvalidSchemaException will be thrown.
     * 
     * @param dbConfig
     *
     * @throws DivIoException
     * @throws InvalidSchemaException 
     * @throws DivException
     *  
     * @since 0.1
     */
    public static Diversicon connectToDb(DBConfig dbConfig) {
        Diversicon ret = new Diversicon(dbConfig);
        return ret;
    }

    /**
     * Exports to a {@code .sql} file. Currently, only supported db is {@code H2}.
     * 
     * @param sqlPath a path to a file, which  is suggested to end with {@code .sql} or {@code .sql.zip}, if compressed.
     *        If the path includes non-existing directories, they will be automatically created. 
     * @param compress if true file is compressed to zip 
     * @throws DivIoException if file in {@code sqlPath} already exists or there are write errors.
     *  
     * @since 0.1
     */
    public void backupToSql(String sqlPath, boolean compress){
        
        checkH2(dbConfig);
        checkNotBlank(sqlPath,  "invalid sql path!");                
        
        File f = new File(sqlPath);
        
        if (f.exists()){
            throw new DivIoException("Tried to export SQL to an already existing file: " 
                                    + f.getAbsolutePath());
        }
        
        LOG.info("Backing up database to " + sqlPath + "  ...");
                
        List<String> params = new ArrayList<>();
         params.add("-url");
         params.add(dbConfig.getJdbc_url());
         params.add("-user");
         params.add(dbConfig.getUser());
         params.add("-password");
         params.add(dbConfig.getPassword());
         params.add("-script");
         params.add(sqlPath);
         
         if (compress){
             params.add("-options");
             params.add("compression");
             params.add("zip");             
         }
                
        String[] bkp = (String[]) params.toArray(new String[0]);
        try {
            Script.main(bkp);
        } catch (SQLException ex) {
            throw new DivIoException("Error while exporting to sql to " + f.getAbsolutePath() + "  !", ex);
        }
        LOG.info("Done backing up database to " + f.getAbsolutePath());
        
    }
           

    /**
     * See {@link #isConnected(String, String, int, List)}
     *
     * @since 0.1
     */
    public boolean isConnected(
            String sourceSynsetId,
            String targetSynsetId,
            int depth,
            String... relNames) {
        return isConnected(sourceSynsetId, targetSynsetId, depth, Arrays.asList(relNames));
    }

    /**
     * 
     * Returns true if {@code sourceSynset} is connected to {@code targetSynset}
     * with some relation
     * {@code relNames} within given {@code depth}. This function only looks for
     * edges already
     * present in the database, without calculating new ones (except for known
     * inverses).
     * 
     * @param sourceSynset
     *            the source synset
     * @param targetSynset
     *            the target synset
     * @param depth
     *            the maximum edge depth for relations.
     *            if {@code -1} no depth limit is applied. If {@code zero}
     *            returns true only if source and target coincide.
     * @param relNames
     *            the relation names (in particular see
     *            {@link ERelNameSemantics}). if none is provided returns true
     *            only if source and target
     *            coincide.
     * 
     * @since 0.1
     */
    public boolean isConnected(
            String sourceSynsetId,
            String targetSynsetId,
            int depth,
            List<String> relNames) {

        checkNotEmpty(sourceSynsetId, "Invalid source synset id!");
        checkNotEmpty(targetSynsetId, "Invalid target synset id!");
        checkNotNull(relNames, "Invalid relation names!");

        checkArgument(depth >= -1, "Depth must be >= -1 , found instead: " + depth);

        if (sourceSynsetId.equals(targetSynsetId)) {
            return true;
        }

        if (relNames.isEmpty()) {
            return false;
        }

        List<String> directRelations = new ArrayList();
        List<String> inverseRelations = new ArrayList();

        for (String relName : relNames) {
            if (Diversicons.isCanonicalRelation(relName) || !Diversicons.hasInverse(relName)) {
                directRelations.add(relName);
            } else {
                inverseRelations.add(Diversicons.getInverse(relName));
            }
        }

        String depthConstraint;
        if (depth == -1) {
            depthConstraint = "";
        } else {
            depthConstraint = " SR.depth <= " + depth + " AND ";
        }

        String directHsql;
        if (directRelations.isEmpty()) {
            directHsql = "";
        } else {
            directHsql = " "
                    + " ("
                    + "     SR.source.id = :sourceSynsetId"
                    + "     AND   SR.target.id = :targetSynsetId"
                    + "     AND   SR.relName IN " + makeSqlList(directRelations)
                    + " )";
        }

        String inverseHsql;
        if (inverseRelations.isEmpty()) {
            inverseHsql = "";
        } else {
            inverseHsql = ""
                    + "  ("
                    + "      SR.source.id = :targetSynsetId"
                    + "      AND SR.target.id = :sourceSynsetId"
                    + "      AND SR.relName IN " + makeSqlList(inverseRelations)
                    + "  )";

        }

        String orHsql;
        if (!directRelations.isEmpty() && !inverseRelations.isEmpty()) {
            orHsql = " OR ";
        } else {
            orHsql = "";
        }

        String queryString = " SELECT 'TRUE'"
                + " FROM SynsetRelation SR"
                + " WHERE "
                + depthConstraint
                + "("
                + directHsql
                + orHsql
                + inverseHsql
                + ")";

        Query query = session.createQuery(queryString);
        query
             .setParameter("sourceSynsetId", sourceSynsetId)
             .setParameter("targetSynsetId", targetSynsetId);

        return query.iterate()
                    .hasNext();
    }

    /**
     * @since 0.1
     */
    private static String formatDate(@Nullable Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM d, yyyy");

        if (date == null) {
            return "missing";
        } else {
            return sdf.format(date);
        }
    }

    /**
     * Returns a nicely formatted import logc
     * 
     * @param fullLog
     *            includes full log in the output
     * @throws DivNotFoundException
     *
     * @since 0.1
     */
    public String formatImportJob(long importJobId, boolean fullLog) {
        ImportJob job = getImportJob(importJobId);
        return formatImportJob(job, fullLog);
    }

    /**
     * Returns a nicely formatted import logc
     * 
     * @param fullLog
     *            includes full log in the output
     * @throws DivNotFoundException
     * 
     * @since 0.1
     */
    public String formatImportJob(ImportJob job, boolean fullLog) {
        StringBuilder sb = new StringBuilder();

        sb.append("IMPORT ID: ");
        sb.append(job.getId());
        sb.append("   LEXICAL RESOURCE: ");
        sb.append(job.getLexicalResourceName());
        sb.append("   IMPORT AUTHOR: ");
        sb.append(job.getAuthor());

        if (job.getLogMessages()
               .size() > 0) {
            sb.append("   THERE WHERE " + job.getLogMessages()
                                             .size()
                    + " WARNINGS/ERRORS");
        }
        sb.append("\n");
        sb.append("STARTED: ");
        sb.append(formatDate(job.getStartDate()));
        sb.append("   ENDED: ");
        sb.append(formatDate(job.getEndDate()));
        sb.append("   FROM FILE: ");
        sb.append(job.getFileUrl());
        sb.append("\n");
        sb.append(job.getDescription());
        sb.append("\n");
        if (fullLog) {
            sb.append("\n");
            sb.append("Import " + job.getId() + "full log:\n");
            for (LogMessage msg : job.getLogMessages()) {
                sb.append(msg.getMessage() + "\n");
            }
        }
        sb.append("\n");
        return sb.toString();

    }

    /**
     * Returns a nicely formatted import log
     * 
     * @param showFullLogs
     *            includes full logs in the output
     * 
     * @see #formatImportJob(ImportJob, boolean)
     * 
     * @since 0.1
     */
    public String formatImportJobs(boolean showFullLogs) {
        StringBuilder sb = new StringBuilder();

        List<ImportJob> importJobs = session.createCriteria(ImportJob.class)
                                            .addOrder(Order.desc("startDate"))
                                            .setMaxResults(50)
                                            .list();

        if (importJobs.isEmpty()) {
            sb.append("No imports were done.\n");
        }

        for (ImportJob job : importJobs) {
            sb.append(formatImportJob(job, showFullLogs));
        }
        return sb.toString();
    }

    /**
     * A list of the imports performed so far.
     * 
     * @since 0.1
     *
     */
    public List<ImportJob> getImportJobs() {
        Criteria crit = session.createCriteria(ImportJob.class);
        List<ImportJob> ret = crit.list();
        return ret;
    }

    /**
     * Returns a nicely formatted status of the database
     * 
     * @param shortProcessedInfo
     *            if true no distinction is made
     *            between graph normalization and augmentation
     * 
     * @since 0.1
     */
    public String formatDbStatus(boolean shortProcessedInfo) {
        StringBuilder sb = new StringBuilder();
        DbInfo dbInfo = getDbInfo();

        sb.append(" Schema version: " + dbInfo.getSchemaVersion());
        sb.append("   Data version: " + dbInfo.getVersion() + "\n");
        sb.append("\n");

        if (shortProcessedInfo) {
            if (dbInfo.isToAugment() || dbInfo.isToNormalize()) {
                sb.append("- Synset relation graph needs to be processed. \n");
            }

        } else {
            if (dbInfo.isToNormalize()) {
                sb.append("- Synset relation graph needs to be normalized.\n");
            }

            if (dbInfo.isToAugment()) {
                sb.append("- Synset relation graph needs to be augmented.\n");
            }

        }

        ImportJob importJob = dbInfo.getCurrentImportJob();

        if (importJob != null) {
            sb.append("- There is an import job in progress for lexical resource "
                    + importJob.getLexicalResourceName() + " from file " + importJob.getFileUrl() + "\n");
        }
        return sb.toString();

    }

    /**
     * 
     * Returns an import job.
     * 
     * @param importId
     *            must be >= 0;
     * 
     * @throws DivNotFoundException
     * 
     * @since 0.1
     */
    public ImportJob getImportJob(long importId) {

        ImportJob ret = (ImportJob) session.get(ImportJob.class, importId);
        if (ret == null) {
            throw new DivNotFoundException("Couldn't find import jod " + importId);
        } else {
            return ret;
        }

    }

}